(ns akvo.lumen.lib.transformation.delete-column
  (:require [akvo.lumen.lib.transformation.engine :as engine]
            [akvo.lumen.util :as util]
            [akvo.lumen.db.transformation.engine :as db.tx.engine]
            [clojure.tools.logging :as log]            
            [clojure.string :as str]
            [akvo.lumen.lib.transformation.merge-datasets :as merge-datasets]))

(defn col-name [op-spec]
  (get (engine/args op-spec) "columnName"))

(defmethod engine/valid? "core/delete-column"
  [op-spec]
  (util/valid-column-name? (col-name op-spec)))

(defn- merged-sources-with-column
  "search for merged-datasets with this column related"
  [tenant-conn column-name dataset-id]
  (->> (merge-datasets/sources-related tenant-conn dataset-id)
       (mapv (juxt merge-datasets/distinct-columns :origin))
       (filter (fn [[distinct-columns _]]
                 (not-empty (filter #(= % column-name) distinct-columns))))))

(defmethod engine/apply-operation "core/delete-column"
  [{:keys [tenant-conn]} table-name columns op-spec]
  (let [column-name (col-name op-spec)
        merged-sources (merged-sources-with-column tenant-conn column-name (:dataset-id op-spec))]
    (if (empty? merged-sources)
      (let [column-idx  (engine/column-index columns column-name)]
        (db.tx.engine/delete-column tenant-conn {:table-name table-name :column-name column-name})
        {:success?      true
         :execution-log [(format "Deleted column %s" column-name)]
         :columns       (into (vec (take column-idx columns))
                              (drop (inc column-idx) columns))})
      {:success? false
       :message  (format "Cannot delete column. It is used in merge transformations of dataset: %s"
                         (str/join "," (map (comp :title second) merged-sources)))})))

(defmethod engine/columns-used "core/delete-column"
  [applied-transformation columns]
  [(:columnName (:args applied-transformation))])

(defmethod engine/avoidable-if-missing? "core/delete-column"
  [applied-transformation]
  true)
