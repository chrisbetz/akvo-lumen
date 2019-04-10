(ns akvo.lumen.endpoint.dataset
  (:require [akvo.lumen.component.error-tracker :as error-tracker]
            [akvo.lumen.component.flow]
            [akvo.lumen.component.keycloak :as keycloak]
            [akvo.lumen.component.tenant-manager :as tenant-manager]
            [akvo.lumen.lib :as lib]
            [akvo.lumen.lib.auth :as l.auth]
            [akvo.lumen.lib.dataset :as dataset]
            [akvo.lumen.protocols :as p]
            [akvo.lumen.specs.dataset :as dataset.s]
            [akvo.lumen.upload :as upload]
            [clojure.spec.alpha :as s]
            [clojure.tools.logging :as log]
            [clojure.walk :as w]
            [integrant.core :as ig]))

(defn all-datasets [auth-service tenant-conn]
  (let [res (dataset/all* tenant-conn)
        ids (l.auth/ids ::dataset.s/datasets res)
        auth-datasets (:auth-datasets (p/auth auth-service ids))
        auth-res (filter #(contains? auth-datasets (:id %)) res)]
    (lib/ok auth-res)))

(defn routes [{:keys [upload-config import-config error-tracker tenant-manager] :as opts}]
  ["/datasets"
   ["" {:get {:handler (fn [{tenant :tenant
                             auth-service :auth-service}]
                         (all-datasets auth-service (p/connection tenant-manager tenant)))}
        :post {:parameters {:body map?}
               :handler (fn [{tenant :tenant
                              jwt-claims :jwt-claims
                              body :body}]
                          (dataset/create (p/connection tenant-manager tenant) (merge import-config upload-config)
                                          error-tracker jwt-claims (w/stringify-keys body)))}}]
   ["/:id" [["" {:get {:parameters {:path-params {:id string?}}
                       :handler (fn [{tenant :tenant
                                      {:keys [id]} :path-params}]
                                  (dataset/fetch (p/connection tenant-manager tenant) id))}
                 :put {:parameters {:body map?
                                    :path-params {:id string?}}
                       :handler (fn [{tenant :tenant
                                      body :body
                                      {:keys [id]} :path-params}]
                                  (dataset/update-meta (p/connection tenant-manager tenant) id body))}
                 :delete {:parameters {:path-params {:id string?}}
                          :handler (fn [{tenant :tenant
                                         {:keys [id]} :path-params}]
                                     (dataset/delete (p/connection tenant-manager tenant) id))}}]
            ["/meta" {:get {:parameters {:path-params {:id string?}}
                            :handler (fn [{tenant :tenant
                                           {:keys [id]} :path-params}]
                                       (dataset/fetch-metadata (p/connection tenant-manager tenant) id))}}]
            ["/update" {:post {:parameters {:path-params {:id string?}}
                               :handler (fn [{tenant :tenant
                                              jwt-claims :jwt-claims
                                              body :body
                                              {:keys [id]} :path-params}]
                                          (dataset/update (p/connection tenant-manager tenant) (merge import-config upload-config)
                                                          error-tracker id (w/stringify-keys body)))}}]]]])


(defmethod ig/init-key :akvo.lumen.endpoint.dataset/dataset  [_ opts]
  (routes opts))

(s/def ::upload-config ::upload/config)
(s/def ::flow-api :akvo.lumen.component.flow/config)
(s/def ::import-config (s/keys :req-un [::flow-api]))
(s/def ::config (s/keys :req-un [::tenant-manager/tenant-manager
                                 ::error-tracker/error-tracker
                                 ::upload-config
                                 ::import-config]))

(defmethod ig/pre-init-spec :akvo.lumen.endpoint.dataset/dataset [_]
  ::config)
