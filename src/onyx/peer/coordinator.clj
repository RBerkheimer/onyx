(ns onyx.peer.coordinator
  (:require [com.stuartsierra.component :as component]
            [onyx.schema :as os]
            [clojure.core.async :refer [alts!! <!! >!! <! >! poll! timeout promise-chan 
                                        dropping-buffer chan close! thread]]
            [onyx.static.planning :as planning]
            [taoensso.timbre :refer [debug info error warn trace fatal]]
            [schema.core :as s]
            [onyx.monitoring.measurements :refer [emit-latency emit-latency-value]]
            [com.stuartsierra.component :as component]
            [onyx.messaging.protocols.messenger :as m]
            [onyx.messaging.protocols.publisher :as pub]
            [onyx.messaging.messenger-state :as ms]
            [onyx.static.util :refer [ms->ns]]
            [onyx.extensions :as extensions :refer [read-checkpoint-coordinate 
                                                    assume-checkpoint-coordinate
                                                    write-checkpoint-coordinate]]
            [onyx.log.replica]
            [onyx.static.default-vals :refer [arg-or-default]])
  (:import [org.apache.zookeeper KeeperException$BadVersionException]
           [java.util.concurrent.locks LockSupport]))

(defn input-publications [{:keys [peer-sites message-short-ids] :as replica} peer-id job-id]
  (let [allocations (get-in replica [:allocations job-id])
        input-tasks (get-in replica [:input-tasks job-id])
        coordinator-peer-id [:coordinator peer-id]
        ;; all input peers receive barriers on slot -1
        slot-id -1]
    (->> input-tasks
         (mapcat (fn [task]
                   (->> (get allocations task)
                        (group-by (fn [input-peer]
                                    (get peer-sites input-peer)))
                        (map (fn [[site colocated-peers]]
                               {:src-peer-id coordinator-peer-id
                                :dst-task-id [job-id task]
                                :dst-peer-ids (set colocated-peers)
                                :short-id (get message-short-ids 
                                               {:src-peer-type :coordinator
                                                :src-peer-id peer-id
                                                :job-id job-id
                                                :dst-task-id task
                                                :msg-slot-id slot-id})
                                :slot-id slot-id
                                :site site})))))
         (set))))

(defn offer-heartbeats
  [{:keys [messenger] :as state}]
  (run! pub/offer-heartbeat! (m/publishers messenger))
  (assoc state :last-heartbeat-time (System/nanoTime)))

(defn offer-barriers
  [{:keys [messenger rem-barriers barrier-opts offering?] :as state}]
  (if offering? 
    (let [_ (run! pub/poll-heartbeats! (m/publishers messenger))
          offer-xf (comp (map (fn [pub]
                                [(m/offer-barrier messenger pub barrier-opts) 
                                 pub]))
                         (remove (comp pos? first))
                         (map second))
          new-remaining (sequence offer-xf rem-barriers)]
      (if (empty? new-remaining)
        (-> state 
            (assoc :last-barrier-time (System/nanoTime))
            (assoc :checkpoint-version nil)
            (assoc :offering? false)
            (assoc :rem-barriers nil))   
        (assoc state :rem-barriers new-remaining)))
    state))

(defn emit-reallocation-barrier 
  [{:keys [peer-config resume-point log job-id peer-id messenger curr-replica] :as state} 
   new-replica]
  (let [replica-version (get-in new-replica [:allocation-version job-id])
        {:keys [onyx/tenancy-id]} peer-config
        new-messenger (-> messenger 
                          (m/update-publishers (input-publications new-replica peer-id job-id))
                          (m/set-replica-version! replica-version)
                          (m/set-epoch! 0)
                          ;; immediately bump to next epoch
                          (m/set-epoch! 1))
        coordinates (read-checkpoint-coordinate log tenancy-id job-id)]
    (assoc state 
           :last-barrier-time (System/nanoTime)
           :offering? true
           :barrier-opts {:recover-coordinates coordinates}
           :rem-barriers (m/publishers new-messenger)
           :curr-replica new-replica
           :messenger new-messenger)))

(defn write-coordinate [curr-version log tenancy-id job-id coordinate]
  (try 
   (->> curr-version
        (write-checkpoint-coordinate log tenancy-id job-id coordinate)
        (:version))
   (catch KeeperException$BadVersionException bve
     (info bve "Coordinator failed to write coordinates.
                This is likely due to job completion writing final job coordinates.")
     curr-version)))

(defn periodic-barrier 
  [{:keys [peer-config zk-version workflow-depth log 
           curr-replica job-id messenger offering?] :as state}]
  (if offering?
    ;; No op because hasn't finished emitting last barrier, wait again
    state
    (let [;; write latest checkpoint coordinates 
          ;; do not allow write to succeed if we are writing to the wrong zookeeper version
          ;; for the coordinate node, as another coordinate may have taken over, or the 
          ;; final coordinates may have already been written by the :complete-job log entry
          {:keys [onyx/tenancy-id]} peer-config
          job-sealed? (boolean (get-in curr-replica [:completed-job-coordinates job-id]))
          first-snapshot-epoch 2
          write-coordinate? (and (not job-sealed?)
                                 (>= (m/epoch messenger) 
                                     (+ first-snapshot-epoch workflow-depth)))
          checkpointed-epoch (- (m/epoch messenger) workflow-depth)
          coordinates {:tenancy-id tenancy-id
                       :job-id job-id
                       :replica-version (m/replica-version messenger) 
                       :epoch checkpointed-epoch}
          ;; get the next version of the zk node, so we can detect when there are other writers
          new-zk-version (if write-coordinate?
                           (write-coordinate zk-version log tenancy-id job-id coordinates)
                           zk-version)
          messenger (m/set-epoch! messenger (inc (m/epoch messenger)))] 
      (assoc state 
             :offering? true
             :zk-version new-zk-version
             :barrier-opts {:checkpointed-epoch (if write-coordinate? (:epoch coordinates))}
             :rem-barriers (m/publishers messenger)
             :messenger messenger))))

(defn shutdown [{:keys [peer-config log workflow-depth job-id messenger] :as state}]
  (assoc state :messenger (component/stop messenger)))

(defn initialise-state [{:keys [log job-id peer-config] :as state}]
  (let [{:keys [onyx/tenancy-id]} peer-config
        zk-version (assume-checkpoint-coordinate log tenancy-id job-id)] 
    (-> state 
        (assoc :zk-version zk-version)
        (assoc :last-barrier-time (System/nanoTime))
        (assoc :last-heartbeat-time (System/nanoTime)))))

(defn start-coordinator! 
  [{:keys [allocation-ch shutdown-ch peer-config] :as state}]
  (thread
   (try
    (let [;snapshot-every-n (arg-or-default :onyx.peer/coordinator-snapshot-every-n-barriers peer-config)
          ;; FIXME: allow in job data
          coordinator-max-sleep-ns (ms->ns (arg-or-default :onyx.peer/coordinator-max-sleep-ms peer-config))
          barrier-period-ns (ms->ns (arg-or-default :onyx.peer/coordinator-barrier-period-ms peer-config))
          heartbeat-ns (ms->ns (arg-or-default :onyx.peer/heartbeat-ms peer-config))] 
      (loop [state (initialise-state state)]
        (if-let [scheduler-event (poll! shutdown-ch)]
          (shutdown (assoc state :scheduler-event scheduler-event))
          (if-let [new-replica (poll! allocation-ch)]
            ;; Set up reallocation barriers. Will be sent on next recur through :offer-barriers
            (recur (emit-reallocation-barrier state new-replica))
            (cond (< (+ (:last-heartbeat-time state) heartbeat-ns) (System/nanoTime))
                  ;; Immediately offer heartbeats
                  (recur (offer-heartbeats state))

                  (:offering? state)
                  ;; Continue offering barriers until success
                  (recur (offer-barriers state)) 

                  (< (+ (:last-barrier-time state) barrier-period-ns) 
                     (System/nanoTime))
                  ;; Setup barriers, will be sent on next recur through :offer-barriers
                  (recur (periodic-barrier state))

                  :else
                  (do
                   (LockSupport/parkNanos coordinator-max-sleep-ns)
                   (recur state)))))))
    (catch Throwable e
      (>!! (:group-ch state) [:restart-vpeer (:peer-id state)])
      (fatal e "Error in coordinator")))))

(defprotocol Coordinator
  (start [this])
  (stop [this scheduler-event])
  (started? [this])
  (send-reallocation-barrier? [this old-replica new-replica])
  (next-state [this old-replica new-replica]))

(defn next-replica [{:keys [allocation-ch] :as coordinator} replica]
  (when (started? coordinator) 
    (>!! allocation-ch replica))
  coordinator)

(defn start-messenger [messenger replica job-id]
  (-> (component/start messenger) 
      (m/set-replica-version! (get-in replica [:allocation-version job-id] -1))))

(defn stop-coordinator! [{:keys [shutdown-ch allocation-ch]} scheduler-event]
  (when shutdown-ch
    (>!! shutdown-ch scheduler-event)
    (close! shutdown-ch))
  (when allocation-ch 
    (close! allocation-ch)))

(defrecord PeerCoordinator 
  [workflow resume-point log messenger-group peer-config peer-id job-id
   messenger group-ch allocation-ch shutdown-ch coordinator-thread]
  Coordinator
  (start [this] 
    (info "Piggybacking coordinator on peer:" peer-id)
    (let [initial-replica (onyx.log.replica/starting-replica peer-config)
          messenger (-> (m/build-messenger peer-config messenger-group [:coordinator peer-id])
                        (start-messenger initial-replica job-id)) 
          allocation-ch (chan (dropping-buffer 1))
          shutdown-ch (promise-chan)
          workflow-depth (planning/workflow-depth workflow)]
      (assoc this 
             :started? true
             :allocation-ch allocation-ch
             :shutdown-ch shutdown-ch
             :messenger messenger
             :coordinator-thread (start-coordinator! 
                                   {:workflow-depth workflow-depth
                                    :resume-point resume-point
                                    :log log
                                    :peer-config peer-config 
                                    :messenger messenger 
                                    :curr-replica initial-replica 
                                    :job-id job-id
                                    :peer-id peer-id 
                                    :group-ch group-ch
                                    :allocation-ch allocation-ch 
                                    :shutdown-ch shutdown-ch}))))
  (started? [this]
    (true? (:started? this)))
  (stop [this scheduler-event]
    (info "Stopping coordinator on:" peer-id)
    (stop-coordinator! this scheduler-event)
    (info "Coordinator stopped.")
    (assoc this :allocation-ch nil :started? false :shutdown-ch nil :coordinator-thread nil))
  (send-reallocation-barrier? [this old-replica new-replica]
    (and (some #{job-id} (:jobs new-replica))
         (not= (get-in old-replica [:allocation-version job-id])
               (get-in new-replica [:allocation-version job-id]))))
  (next-state [this old-replica new-replica]
    (let [started? (= (get-in old-replica [:coordinators job-id]) peer-id)
          start? (= (get-in new-replica [:coordinators job-id]) peer-id)]
      (cond-> this
        (and (not started?) start?)
        (start)

        (and started? (not start?))
        (stop :rescheduled)

        (send-reallocation-barrier? this old-replica new-replica)
        (next-replica new-replica)))))

(defn new-peer-coordinator 
  [workflow resume-point log messenger-group peer-config peer-id job-id group-ch]
  (map->PeerCoordinator {:workflow workflow
                         :resume-point resume-point
                         :log log
                         :group-ch group-ch
                         :messenger-group messenger-group 
                         :peer-config peer-config 
                         :peer-id peer-id 
                         :job-id job-id}))