(ns akvo.lumen.lib.visualisation-impl
  (:require [akvo.lumen.lib :as lib]
            [akvo.lumen.util :refer [squuid]]
            [hugsql.core :as hugsql])
  (:import [java.sql SQLException]))


(hugsql/def-db-fns "akvo/lumen/lib/visualisation.sql")


(defn all [tenant-conn]
  (lib/ok (all-visualisations tenant-conn
                              {}
                              {}
                              {:identifiers identity})))


(defn create [tenant-conn body jwt-claims]
  (let [id (squuid)
        v (first (upsert-visualisation tenant-conn
                                       {:id id
                                        :dataset-id (get body "datasetId")
                                        :type (get body "visualisationType")
                                        :name (get body "name")
                                        :spec (get body "spec")
                                        :author jwt-claims}))]
    (lib/ok (assoc body
                   "id" (str id)
                   "status" "OK"
                   "created" (:created v)
                   "modified" (:modified v)))))

(defn fetch [tenant-conn id]
  (if-let [v (visualisation-by-id tenant-conn
                                  {:id id}
                                  {}
                                  {:identifiers identity})]
    (lib/ok (dissoc v :author))
    (lib/not-found {:error "Not found"})))

(defn upsert [tenant-conn body jwt-claims]
  (let [v (upsert-visualisation tenant-conn
                                {:id (get body "id")
                                 :dataset-id (get body "datasetId")
                                 :type (get body "visualisationType")
                                 :name (get body "name")
                                 :spec (get body "spec")
                                 :author jwt-claims})]
    (lib/ok {:id (-> v first :id)})))

(defn delete [tenant-conn id]
  (if (zero? (delete-visualisation-by-id tenant-conn {:id id}))
    (lib/not-found {:error "Not found"})
    (lib/ok {:id id})))

(defn test-map-for-dataset-id [tenant-conn viz dataset-id]
  (if (some
        (fn [layer]
          (or
            (= (get layer "datasetId") dataset-id)
            (= (get layer "aggregationDataset") dataset-id)
          )
        )
        (get-in viz [:spec "layers"])
      )
      (delete tenant-conn (get viz :id))
  )
)

(defn delete-maps-by-dataset-id [tenant-conn dataset-id]
  (let [all (all-visualisations tenant-conn)]
    (doseq [x all] (test-map-for-dataset-id tenant-conn x dataset-id))
  )
)
