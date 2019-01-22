(ns akvo.lumen.lib.public
  (:require [akvo.lumen.lib :as lib]
            [akvo.lumen.lib.aggregation :as aggregation]
            [akvo.lumen.lib.dashboard :as dashboard]
            [akvo.lumen.lib.dataset :as dataset]
            [akvo.lumen.lib.visualisation :as visualisation]
            [akvo.lumen.lib.visualisation.maps :as maps]
            [cheshire.core :as json]
            [clojure.tools.logging :as log]
            [clojurewerkz.scrypt.core :as scrypt]
            [clojure.walk :refer (keywordize-keys)]
            [clojure.set :as set]
            [hugsql.core :as hugsql]))

(hugsql/def-db-fns "akvo/lumen/lib/public.sql")

(defn get-share [tenant-conn id]
  (public-by-id tenant-conn {:id id}))

(def vis-aggregation-mapper {"pivot table" "pivot"
                             "line"      "line"
                             "bubble"    "bubble"
                             "area"      "line"
                             "pie"       "pie"
                             "donut"     "donut"
                             "polararea" "pie"
                             "bar"       "bar"
                             "scatter"   "scatter"})

(defn run-visualisation 
  [tenant-conn visualisation]
  (let [visualisation (keywordize-keys visualisation)
        [dataset-tag dataset] (dataset/fetch-metadata tenant-conn (:datasetId visualisation))
        aggregation-type (get vis-aggregation-mapper (:visualisationType visualisation))
        [tag query-result] (aggregation/query tenant-conn
                                              (:datasetId visualisation)
                                              aggregation-type
                                              (:spec visualisation))]
    (when (and (= tag ::lib/ok)
               (= dataset-tag ::lib/ok))
      {"visualisations" {(:id visualisation) (assoc visualisation :data query-result)}
       "datasets" { (:id dataset) dataset}})))

(defn run-map-visualisation
  [tenant-conn visualisation {:keys [windshaft-url]}]
  (let [layers (get-in visualisation [:spec "layers"])]
    (if (some #(get % "datasetId") layers)
      (let [dataset-id (some #(get % "datasetId") layers)
              [map-data-tag map-data] (maps/create tenant-conn windshaft-url layers)
              [dataset-tag dataset] (dataset/fetch-metadata tenant-conn dataset-id)]
          (when (and (= map-data-tag ::lib/ok)
                     (= dataset-tag ::lib/ok))
            {"datasets" {dataset-id dataset}
             "visualisations" {(:id visualisation) (merge visualisation map-data)}
             "metadata" {(:id visualisation) map-data}}))
      (let [[map-data-tag map-data] (maps/create tenant-conn windshaft-url layers)]
          (when (= map-data-tag ::lib/ok)
            {"visualisations" {(:id visualisation) (merge visualisation map-data)}
             "metadata" {(:id visualisation) map-data}})))))

(defn run-unknown-type-visualisation 
  [tenant-conn visualisation]
  (let [dataset-id (:datasetId visualisation)
        [tag dataset] (dataset/fetch-metadata tenant-conn dataset-id)]
    (when (= tag ::lib/ok)
      {"datasets" {dataset-id dataset}
       "visualisations" {(:id visualisation) visualisation}})))

(defn visualisation-response-data [tenant-conn id config]
  (try
    (let [[tag vis] (visualisation/fetch tenant-conn id)]
      (when (= tag ::lib/ok)
        (condp contains? (:visualisationType vis)
          #{"map"} (run-map-visualisation tenant-conn vis config)
          (set (keys vis-aggregation-mapper)) (run-visualisation tenant-conn vis)
          (run-unknown-type-visualisation tenant-conn vis))))
    (catch Exception e
      (log/warn e ::visualisation-response-data (str "problems fetching this vis-id: " id)))))

(defn dashboard-response-data [tenant-conn id config]
  (let [[tag dashboard] (dashboard/fetch tenant-conn id)]
    (when (= tag ::lib/ok)
      (let [deps (->> dashboard
                      :entities
                      vals
                      (filter #(= "visualisation" (get % "type")))
                      (map #(get % "id"))
                      (map #(visualisation-response-data tenant-conn % config))
                      (sort-by #(-> % (get "datasets") vals first (get :rows) boolean))
                      (apply merge-with merge))]
        (assoc deps "dashboards" {id dashboard})))))

(defn response-data [tenant-conn share config]
  (if-let [dashboard-id (:dashboard-id share)]
    (assoc (dashboard-response-data tenant-conn dashboard-id config)
           "dashboardId" dashboard-id)
    (let [visualisation-id (:visualisation-id share)]
      (assoc (visualisation-response-data tenant-conn visualisation-id config)
             "visualisationId" visualisation-id))))

(defn share
  [tenant-conn config id password]
  (if-let [share (get-share tenant-conn id)]
    (if (:protected share)
      (if (scrypt/verify (format "%s|%s" id password) (:password share))
        (lib/ok (response-data tenant-conn share config))
        (lib/not-authorized {"shareId" id}))
      (lib/ok (response-data tenant-conn share config)))
    (lib/not-found {"shareId" id})))
