(ns akvo.lumen.transformation.derive
  (:require [akvo.lumen.transformation.derive.js-engine :as js-engine]
            [akvo.lumen.transformation.engine :as engine]
            [clj-time.coerce :as tc]
            [akvo.lumen.dataset.utils :as dataset.utils]
            [akvo.lumen.util :as util]
            [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging :as log]
            [clojure.string :as str]
            [hugsql.core :as hugsql]))

(hugsql/def-db-fns "akvo/lumen/transformation/derive.sql")
(hugsql/def-db-fns "akvo/lumen/transformation/engine.sql")

(defn construct-code
  "Replace column references and fall back to use code pattern if there is no
  references."
  [columns transformation]
  (let [code (reduce (fn [code {:strs [column-name id pattern]}]
                       (let [column-title (try
                                            (get (dataset.utils/find-column columns column-name) "title")
                                            (catch Exception e (do
                                                                 (log/error "jor" column-name)
                                                                 nil)))]
                         (if column-name
                           (str/replace code id (format "row['%s']" column-title))
                           (str/replace code id pattern))))
                     (get-in transformation ["computed" "template"])
                     (get-in transformation ["computed" "references"]))]
    code))

(defmethod engine/parse-tx :core/derive
  [op-spec columns]
  (assoc-in op-spec ["computed" "code"] (construct-code columns op-spec)))

(defn lumen->pg-type [type]
  (condp = type
    "text"   "text"
    "number" "double precision"
    "date"   "timestamptz"))

(defn args [op-spec]
  (let [{code         "code"
         column-title "newColumnTitle"
         column-type  "newColumnType"} (engine/args op-spec)]
    {::code code ::column-title column-title ::column-type column-type}))

(defmethod engine/valid? :core/derive
  [op-spec]
  (let [{:keys [::code
                ::column-title
                ::column-type]} (args op-spec)]
    (and (string? column-title) 
         (engine/valid-type? column-type)
         (#{"fail" "leave-empty" "delete-row"} (engine/error-strategy op-spec))
         (js-engine/evaluable? code))))

(defn js-execution>sql-params [js-seq result-kw]
  (->> js-seq
       (filter (fn [[j r i]]
                 (= r result-kw)))
       (map (fn [[i _ v]] [i v]))))

(defn set-cells-values! [conn opts data]
  (->> data
       (map (fn [[i v]] (set-cell-value conn (merge {:value v :rnum i} opts))))
       doall))

(defn delete-rows! [conn opts data]
  (->> data
       (map (fn [[i]] (delete-row conn (merge {:rnum i} opts))))
       doall))

(defmethod engine/apply-operation :core/derive
  [{:keys [tenant-conn]} table-name columns op-spec]
  (jdbc/with-db-transaction [conn tenant-conn]
    (let [{:keys [::code
                  ::column-title
                  ::column-type]} (args op-spec)
          new-column-name         (engine/next-column-name columns)
          row-fn                  (js-engine/row-transform-fn {:columns     columns
                                                               :code        (construct-code columns op-spec)
                                                               :column-type column-type})
          js-execution-seq        (->> (all-data conn {:table-name table-name})
                                       (map (fn [i]
                                              (try
                                                [(:rnum i) :set-value! (row-fn i)]
                                                (catch Exception e
                                                  (condp = (engine/error-strategy op-spec)
                                                    "leave-empty" [(:rnum i) :set-value! nil]
                                                    "delete-row"  [(:rnum i) :delete-row!]
                                                    "fail"        (throw e) ;; interrupt js execution
                                                    ))))))
          base-opts               {:table-name  table-name
                                   :column-name new-column-name}]
      (add-column conn {:table-name      table-name
                        :column-type     (lumen->pg-type column-type)
                        :new-column-name new-column-name})
      (set-cells-values! conn base-opts (js-execution>sql-params js-execution-seq :set-value!))
      (delete-rows! conn base-opts (js-execution>sql-params js-execution-seq :delete-row!))      
      {:success?      true
       :execution-log [(format "Derived columns using '%s'" code)]
       :columns       (conj columns {"title"      column-title
                                     "type"       column-type
                                     "sort"       nil
                                     "hidden"     false
                                     "direction"  nil
                                     "columnName" new-column-name})})))

(defn parse-row-object-references
  "Parse js code and return a sequence of row-references e.g. row.foo row['foo']
  or row[\"foo\"]. For every reference return a tuple with matched pattern and
  the row column as in [\"row.foo\" \"foo\"]."
  [code]
  (let [re #"(?U)row.([\w\d]+)|row\['([\w\d\.\s\p{S}%&]+)'\]|row\[\"([\w\d\.\s\p{S}%&]+)\"\]"
        refs (map #(remove nil? %) (re-seq re code))]
    (if (empty? refs)
      `([~code ~code])
      refs)))

(defmethod engine/pre-hook :core/derive
  [transformation columns]
  (let [code (get-in transformation ["args" "code"]) 
        computed (reduce (fn [m [pattern column-title]]
            (let [id (str (util/squuid))]
              (-> m
                  (update-in ["template"] #(str/replace % pattern id))
                  (update-in ["references"]
                             #(conj % {"id" id
                                       "pattern" pattern
                                       "column-name" (try
                                                       (get (dataset.utils/find-column columns column-title "title") "columnName")
                                                       (catch Exception e nil))})))))
          {"template" code
           "references" []}
          (parse-row-object-references code))]
    (assoc transformation "computed" computed)))
