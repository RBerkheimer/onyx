(ns onyx.log.entry
  (:require [onyx.log.commands.prepare-join-cluster]
            [onyx.log.commands.accept-join-cluster]
            [onyx.log.commands.abort-join-cluster]
            [onyx.log.commands.notify-join-cluster]
            [onyx.log.commands.leave-cluster]
            [onyx.log.commands.submit-job]
            [onyx.log.commands.volunteer-for-task]
            [onyx.log.commands.complete-task]))

(defn create-log-entry [kw args]
  {:fn kw :args args})

