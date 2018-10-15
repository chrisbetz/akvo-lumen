(ns akvo.lumen.endpoint.export
  "Exposes the exporter proxy"
  (:require [akvo.lumen.lib.export :as export]
            [compojure.core :refer :all]))


(defn endpoint [{{exporter-api-url :exporter-api-url} :config}]
  (context "/api/exports" _
           (POST "/" {:keys [body jwt-claims headers]}
                 (let [exporter-url (str exporter-api-url "/screenshot")]
                   (export/export exporter-url
                                  (get headers "authorization")
                                  (get jwt-claims "locale")
                                  body)))))