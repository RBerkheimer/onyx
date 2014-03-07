(ns onyx.coordinator.single-peer-test
  (:require [midje.sweet :refer :all]
            [clojure.core.async :refer [chan tap >!! <!!]]
            [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [onyx.extensions :as extensions]
            [onyx.coordinator.async :as async]
            [onyx.coordinator.log.datomic :as datomic]
            [onyx.coordinator.sim-test-utils :refer [with-system]]))

(facts
 "new peer"
 (with-system
   (fn [coordinator sync log]
     (let [peer (extensions/create sync :peer)
           pulse (extensions/create sync :pulse)
           shutdown (extensions/create sync :shutdown)
           offer-ch-spy (chan 1)
           failure-ch-spy (chan 1)]

       (extensions/write-place sync peer {:pulse pulse :shutdown shutdown})
             
       (tap (:offer-mult coordinator) offer-ch-spy)
       (tap (:failure-mult coordinator) failure-ch-spy)
             
       (>!! (:born-peer-ch-head coordinator) peer)
       (<!! offer-ch-spy)

       (facts "There is one peer"
              (let [query '[:find ?p :where [?e :node/peer ?p]]
                    result (d/q query (d/db (:conn log)))]
                (fact (count result) => 1)
                (fact (ffirst result) => peer)))))))

(facts
 "peer joins and dies"
 (with-system
   (fn [coordinator sync log]
     (let [peer (extensions/create sync :peer)
           pulse (extensions/create sync :pulse)
           shutdown (extensions/create sync :shutdown)
           offer-ch-spy (chan 1)
           evict-ch-spy (chan 1)
           shutdown-ch-spy (chan 1)
           failure-ch-spy (chan 1)]

       (extensions/write-place sync peer {:pulse pulse :shutdown shutdown})
             
       (tap (:offer-mult coordinator) offer-ch-spy)
       (tap (:evict-mult coordinator) evict-ch-spy)
       (tap (:shutdown-mult coordinator) shutdown-ch-spy)
       (tap (:failure-mult coordinator) failure-ch-spy)
             
       (>!! (:born-peer-ch-head coordinator) peer)
       (<!! offer-ch-spy)
       (extensions/delete sync pulse)
       (<!! evict-ch-spy)
       (<!! shutdown-ch-spy)

       (facts "There are no peers"
              (let [query '[:find ?p :where [?e :node/peer ?p]]
                    result (d/q query (d/db (:conn log)))]
                (fact (count result) => zero?)))))))

(facts
 "planning one job with no peers"
 (with-system
   (fn [coordinator sync log]
     (let [catalog [{:onyx/name :in
                     :onyx/direction :input
                     :onyx/consumption :sequential
                     :onyx/type :queue
                     :onyx/medium :hornetq
                     :hornetq/queue-name "in-queue"}
                    {:onyx/name :inc
                     :onyx/type :transformer
                     :onyx/consumption :sequential}
                    {:onyx/name :out
                     :onyx/direction :output
                     :onyx/consumption :sequential
                     :onyx/type :queue
                     :onyx/medium :hornetq
                     :hornetq/queue-name "out-queue"}]
           workflow {:in {:inc :out}}
           offer-ch-spy (chan 1)]

       (tap (:offer-mult coordinator) offer-ch-spy)
             
       (>!! (:planning-ch-head coordinator)
            {:catalog catalog :workflow workflow})

       (let [job-id (<!! offer-ch-spy)
             db (d/db (:conn log))]

         (facts "There is one job"
                (let [query '[:find ?j :in $ ?id :where [?j :job/id ?id]]
                      result (d/q query db job-id)]
                  (fact (count result) => 1)))

         (facts "There are three tasks"
                (let [query '[:find ?n :where [?t :task/name ?n]]]
                  (fact (d/q query db) => #{[:in] [:inc] [:out]})))

         (facts ":in's ingress queue is preset"
                (let [query '[:find ?qs :where
                              [?t :task/name :in]
                              [?t :task/ingress-queues ?qs]]]
                  (fact (d/q query db) => #{["in-queue"]})))

         (facts ":inc's ingress queue is :in's egress queue"
                (let [in-query '[:find ?qs :where
                                 [?t :task/name :in]
                                 [?t :task/egress-queues ?qs]]
                      inc-query '[:find ?qs :where
                                  [?t :task/name :inc]
                                  [?t :task/ingress-queues ?qs]]]
                  (fact (d/q in-query db) => (d/q inc-query db))))

         (facts ":out's ingess queue is :inc's egress queue"
                (let [inc-query '[:find ?qs :where
                                  [?t :task/name :inc]
                                  [?t :task/egress-queues ?qs]]
                      out-query '[:find ?qs :where
                                  [?t :task/name :out]
                                  [?t :task/ingress-queues ?qs]]]
                  (fact (d/q inc-query db) => (d/q out-query db))))

         (facts ":out's egress queue is preset"
                (let [query '[:find ?qs :where
                              [?t :task/name :out]
                              [?t :task/egress-queues ?qs]]]
                  (fact (d/q query db) => #{["out-queue"]}))))))))

(defn test-task-life-cycle
  [{:keys [log sync sync-spy ack-ch-spy completion-ch-spy offer-ch-spy
           peer-node payload-node next-payload-node task-name pulse-node
           shutdown-node]}]
  (facts "The payload node is populated"
         (let [event (<!! sync-spy)]
           (fact (:path event) => payload-node)))

  (let [db (d/db (:conn log))]
    (facts "It receives the task"
           (let [task (:task (extensions/read-place sync payload-node))
                 query '[:find ?task :in $ ?t-name :where
                         [?task :task/name ?t-name]]]
             (fact (:db/id task) => (ffirst (d/q query db task-name)))))

    (facts "The peer is marked as :acking the task"
           (let [query '[:find ?task :in $ ?t-name :where
                         [?peer :peer/status :acking]
                         [?peer :peer/task ?task]
                         [?task :task/name ?t-name]]]
             (fact (count (d/q query db task-name)) => 1)))

    (facts "The payload node contains the other node paths"
           (let [nodes (:nodes (extensions/read-place sync payload-node))]
             (fact (into #{} (keys nodes)) =>
                   #{:payload :ack :completion :status :catalog :workflow :peer})))
    
    (facts "Touching the ack node triggers the callback"
           (let [nodes (:nodes (extensions/read-place sync payload-node))]
             (extensions/touch-place sync (:ack nodes))
             (let [event (<!! ack-ch-spy)]
               (fact (:path event) => (:ack nodes)))))

    (extensions/write-place sync peer-node {:pulse pulse-node
                                            :shutdown shutdown-node
                                            :payload next-payload-node})
    (extensions/on-change sync next-payload-node #(>!! sync-spy %))

    (facts "Touching the completion node triggers the callback"
           (let [nodes (:nodes (extensions/read-place sync payload-node))]
             (extensions/touch-place sync (:completion nodes))
             (let [event (<!! completion-ch-spy)]
               (fact (:path event) => (:completion nodes)))))

    (facts "The offer channel receives the tx id of the completion"
           (let [tx-id (:tx (<!! offer-ch-spy))
                 db (d/as-of (d/db (:conn log)) tx-id)]

             (facts "The peer's nodes have been stripped"
                    (let [query '[:find ?payload ?ack ?status ?completion :in $ ?peer-node :where
                                  [?p :peer/status :idle]
                                  [?p :node/peer ?peer-node]
                                  [?p :node/payload ?payload]
                                  [?p :node/ack ?ack]
                                  [?p :node/status ?status]
                                  [?p :node/completion ?completion]]
                          result (d/q query db peer-node)]
                      (fact result => empty?)))))))

(facts
 "planning one job with one peer"
 (with-system
   (fn [coordinator sync log]
     (let [peer-node (extensions/create sync :peer)
           pulse-node (extensions/create sync :pulse)
           shutdown-node (extensions/create sync :shutdown)
                 
           in-payload-node (extensions/create sync :payload)
           inc-payload-node (extensions/create sync :payload)
           out-payload-node (extensions/create sync :payload)
           future-payload-node (extensions/create sync :payload)
                 
           sync-spy (chan 1)
           ack-ch-spy (chan 1)
           offer-ch-spy (chan 1)
           completion-ch-spy (chan 1)
                 
           catalog [{:onyx/name :in
                     :onyx/direction :input
                     :onyx/type :queue
                     :onyx/medium :hornetq
                     :onyx/consumption :sequential
                     :hornetq/queue-name "in-queue"}
                    {:onyx/name :inc
                     :onyx/type :transformer
                     :onyx/consumption :sequential}
                    {:onyx/name :out
                     :onyx/direction :output
                     :onyx/type :queue
                     :onyx/medium :hornetq
                     :onyx/consumption :sequential
                     :hornetq/queue-name "out-queue"}]
           workflow {:in {:inc :out}}]

       (tap (:ack-mult coordinator) ack-ch-spy)
       (tap (:offer-mult coordinator) offer-ch-spy)
       (tap (:completion-mult coordinator) completion-ch-spy)

       (extensions/write-place sync peer-node {:pulse pulse-node
                                               :shutdown shutdown-node
                                               :payload in-payload-node})
       (extensions/on-change sync in-payload-node #(>!! sync-spy %))

       (>!! (:born-peer-ch-head coordinator) peer-node)
       (>!! (:planning-ch-head coordinator) {:catalog catalog :workflow workflow})

       (<!! offer-ch-spy)
       (<!! offer-ch-spy)

       (let [base-cycle {:log log
                         :sync sync
                         :sync-spy sync-spy
                         :ack-ch-spy ack-ch-spy
                         :offer-ch-spy offer-ch-spy
                         :completion-ch-spy completion-ch-spy
                         :peer-node peer-node
                         :pulse-node pulse-node}]
         (test-task-life-cycle
          (assoc base-cycle
            :task-name :in
            :payload-node in-payload-node
           :next-payload-node inc-payload-node))

         (test-task-life-cycle
          (assoc base-cycle
            :task-name :inc
            :payload-node inc-payload-node
            :next-payload-node out-payload-node))

         (test-task-life-cycle
          (assoc base-cycle
            :task-name :out
            :payload-node out-payload-node
            :next-payload-node future-payload-node)))))
   {:revoke-delay 500000}))

(facts
 "evicting one peer"
 (with-system
   (fn [coordinator sync log]
     (let [catalog [{:onyx/name :in
                     :onyx/direction :input
                     :onyx/consumption :sequential
                     :onyx/type :queue
                     :onyx/medium :hornetq
                     :hornetq/queue-name "in-queue"}
                    {:onyx/name :inc
                     :onyx/type :transformer
                     :onyx/consumption :sequential}
                    {:onyx/name :out
                     :onyx/direction :output
                     :onyx/consumption :sequential
                     :onyx/type :queue
                     :onyx/medium :hornetq
                     :hornetq/queue-name "out-queue"}]
           workflow {:in {:inc :out}}

           peer-node (extensions/create sync :peer)
           pulse-node (extensions/create sync :pulse)
           shutdown-node (extensions/create sync :shutdown)
           payload-node (extensions/create sync :payload)
                 
           sync-spy (chan 1)
           offer-ch-spy (chan 3)]
             
       (tap (:offer-mult coordinator) offer-ch-spy)

       (extensions/write-place sync peer-node {:pulse pulse-node :payload payload-node})
       (extensions/on-change sync payload-node #(>!! sync-spy %))

       (>!! (:born-peer-ch-head coordinator) peer-node)
       (<!! offer-ch-spy)
       (>!! (:planning-ch-head coordinator) {:catalog catalog :workflow workflow})
       (<!! offer-ch-spy)
       (<!! sync-spy)

       ;; Instant revoke.
       (<!! offer-ch-spy)

       (facts "The peer gets deleted after eviction"
              (let [db (d/db (:conn log))
                    query '[:find ?p :in $ ?peer-node :where
                            [?p :node/peer ?peer-node]]]
                (fact (count (d/q query db peer-node)) => zero?)))

       (facts "The status node gets deleted on sync storage"
              (let [db (d/history (d/db (:conn log)))
                    query '[:find ?status :in $ ?peer-node :where
                            [?p :node/peer ?peer-node]
                            [?p :node/status ?status]]
                    status-node (ffirst (d/q query db peer-node))]
                (fact (extensions/read-place sync status-node) => (throws Exception))))))
   {:revoke-delay 0}))

(facts
 "error cases"
 (with-system
   (fn [coordinator sync log]
     (let [peer (extensions/create sync :peer)
           pulse (extensions/create sync :pulse)
           shutdown (extensions/create sync :shutdown)
           offer-ch-spy (chan 5)
           ack-ch-spy (chan 5)
           evict-ch-spy (chan 5)
           completion-ch-spy (chan 5)
           failure-ch-spy (chan 10)]

       (extensions/write-place sync peer {:pulse pulse :shutdown shutdown})
             
       (tap (:offer-mult coordinator) offer-ch-spy)
       (tap (:ack-mult coordinator) ack-ch-spy)
       (tap (:evict-mult coordinator) evict-ch-spy)
       (tap (:completion-mult coordinator) completion-ch-spy)
       (tap (:failure-mult coordinator) failure-ch-spy)

       (>!! (:born-peer-ch-head coordinator) peer)
       (<!! offer-ch-spy)

       (facts "Adding a duplicate peer fails"
              (>!! (:born-peer-ch-head coordinator) peer)
              (let [failure (<!! failure-ch-spy)]
                (fact (:ch failure) => :peer-birth)))

       (facts "Attempts to delete a non-existent peer fails"
              (extensions/delete sync pulse)
              (<!! evict-ch-spy)

              (facts "A failure is raised for the second callback"
                     (let [failure (<!! failure-ch-spy)]
                       (fact (:ch failure) => :peer-death)))
                    
              (facts "A failure is raised for the second delete"
                     (>!! (:dead-peer-ch-head coordinator) peer)
                     (let [failure (<!! failure-ch-spy)]
                       (fact (:ch failure) => :peer-death))))

       (facts "Acking a non-existent node fails"
              (>!! (:ack-ch-head coordinator) {:path (str (java.util.UUID/randomUUID))})
              (let [failure (<!! failure-ch-spy)]
                (fact (:ch failure) => :ack)))

       (facts "Acking a completed task fails"
              (let [peer-id (d/tempid :onyx/log)
                    task-id (d/tempid :onyx/log)
                    node-path (str (java.util.UUID/randomUUID))
                    tx [{:db/id peer-id
                         :peer/status :acking
                         :node/ack node-path
                         :peer/task {:db/id task-id
                                     :task/complete? true}}]]
                @(d/transact (:conn log) tx)
                      
                (>!! (:ack-ch-head coordinator) {:path node-path})
                (let [failure (<!! failure-ch-spy)]
                  (fact (:ch failure) => :ack))))

       (facts "Acking with a peer who's state isnt :acking fails"
              (let [peer-id (d/tempid :onyx/log)
                    task-id (d/tempid :onyx/log)
                    node-path (str (java.util.UUID/randomUUID))
                    tx [{:db/id peer-id
                         :peer/status :idle
                         :node/ack node-path
                         :peer/task {:db/id task-id
                                     :task/complete? false}}]]
                @(d/transact (:conn log) tx)
                      
                (>!! (:ack-ch-head coordinator) {:path node-path})
                (let [failure (<!! failure-ch-spy)]
                  (fact (:ch failure) => :ack))))

       (facts "Completing a task that doesn't exist fails"
              (>!! (:completion-ch-head coordinator) {:path "dead path"})
              (let [failure (<!! failure-ch-spy)]
                (fact (:ch failure) => :complete)))
             
       (facts "Completing a task that's already been completed fails"
              (let [peer-id (d/tempid :onyx/log)
                    task-id (d/tempid :onyx/log)
                    node-path (str (java.util.UUID/randomUUID))
                    tx [{:db/id peer-id
                         :peer/status :active
                         :node/completion node-path
                         :node/payload (str (java.util.UUID/randomUUID))
                         :node/ack (str (java.util.UUID/randomUUID))
                         :node/status (str (java.util.UUID/randomUUID))
                         :peer/task {:db/id task-id
                                     :task/complete? true}}]]
                @(d/transact (:conn log) tx)
                      
                (>!! (:completion-ch-head coordinator) {:path node-path})
                (let [failure (<!! failure-ch-spy)]
                  (fact (:ch failure) => :complete))))

       (facts "Completing a task from an idle peer fails"
              (let [peer-id (d/tempid :onyx/log)
                    task-id (d/tempid :onyx/log)
                    node-path (str (java.util.UUID/randomUUID))
                    tx [{:db/id peer-id
                         :peer/status :idle
                         :node/completion node-path
                         :node/payload (str (java.util.UUID/randomUUID))
                         :node/ack (str (java.util.UUID/randomUUID))
                         :node/status (str (java.util.UUID/randomUUID))
                         :peer/task {:db/id task-id
                                     :task/complete? false}}]]
                @(d/transact (:conn log) tx)
                      
                (>!! (:completion-ch-head coordinator) {:path node-path})
                (let [failure (<!! failure-ch-spy)]
                  (fact (:ch failure) => :complete))))))
   {:revoke-delay 50000}))

