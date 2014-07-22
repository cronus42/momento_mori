(ns aws-snapshot-monkey.core
  (:gen-class)
  (:require [clj-time.core :as tm]
            [clj-time.local :as loc]
            [clj-time.format :as fmt])
  (:use clj-time.coerce)
  (:use [amazonica.aws.ec2])
  (:use [clojure.pprint])
  (:use [clojure.set]))

(defn get_volumes_in_use [] 
  "grab the aws volume-id's for in-use volumes"
  (map 
    (fn [m] (get m :volume-id))
    (get (describe-volumes :state "in-use") :volumes)
    ))

(defn get_snapshots_for_volumes [volumes]
  "fetch the snapshot-id's and volume-id's for given volumes"
  (map
    (fn [m] (select-keys m [:snapshot-id :volume-id :start-time]))
    (get (describe-snapshots :filters [{:name "volume-id" :values volumes}]) :snapshots)
    ))

(defn -main []
  "The main function"
  (def volumes_in_use (into #{} (get_volumes_in_use)))
  (def snaps_in_use (get_snapshots_for_volumes volumes_in_use))
  (def vols_with_snaps (into #{} (keys (group-by :volume-id snaps_in_use))))
  (def vols_wo_snaps (difference volumes_in_use vols_with_snaps))
  ;;TODO: filter the snaps by date
  ;;
  (println "In-use volumes without snapshots:")
  (pprint vols_wo_snaps)
  )

