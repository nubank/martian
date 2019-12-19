(ns martian.swagger
  (:require [camel-snake-kebab.core :refer [->kebab-case-keyword]]
            [clojure.string :as string]
            [clojure.walk :refer [keywordize-keys]]
            [martian.schema :as schema]
            [schema.core :as s]
            #?(:cljs [cljs.reader :refer [read-string]])))

(defn- body-schema [definitions swagger-params]
  (when-let [body-params (not-empty (filter #(= "body" (:in %)) swagger-params))]
    (schema/schemas-for-parameters definitions body-params)))

(defn- form-schema [definitions swagger-params]
  (when-let [form-params (not-empty (filter #(= "formData" (:in %)) swagger-params))]
    (schema/schemas-for-parameters definitions form-params)))

(defn- path-schema [definitions swagger-params]
  (when-let [path-params (not-empty (filter #(= "path" (:in %)) swagger-params))]
    (schema/schemas-for-parameters definitions path-params)))

(defn- query-schema [definitions swagger-params]
  (when-let [query-params (not-empty (filter #(= "query" (:in %)) swagger-params))]
    (schema/schemas-for-parameters definitions query-params)))

(defn- headers-schema [definitions swagger-params]
  (when-let [header-params (not-empty (filter #(= "header" (:in %)) swagger-params))]
    (schema/schemas-for-parameters definitions header-params)))

(defn- response-schemas [definitions swagger-responses]
  (for [[status response] swagger-responses
        :let [status-code (if (number? status) status (read-string (name status)))]]
    {:status (s/eq status-code)
     :body (schema/make-schema definitions (assoc (:schema response) :required true))}))

(defn- sanitise [x]
  (if (string? x)
    x
    ;; consistent across clj and cljs
    (-> (str x)
        (string/replace-first ":" ""))))

(defn- tokenise-path [url-pattern]
  (let [url-pattern (sanitise url-pattern)
        parts (map first (re-seq #"([^{}]+|\{.+?\})" url-pattern))]
    (map #(if-let [param-name (second (re-matches #"^\{(.*)\}" %))]
            (keyword param-name)
            %) parts)))

(defn- ->handler
  [{:keys [definitions] :as swagger-map}
   path-item-parameters
   url-pattern
   [method swagger-definition]]
  (when-let [route-name (some-> (:operationId swagger-definition) ->kebab-case-keyword)]
    (let [path-parts (tokenise-path url-pattern)
          uri (string/join (map str path-parts))
          parameters (concat path-item-parameters (:parameters swagger-definition))]
      {:path uri
       :path-parts path-parts
       :method method
       :path-schema (path-schema definitions parameters)
       :query-schema (query-schema definitions parameters)
       :body-schema (body-schema definitions parameters)
       :form-schema (form-schema definitions parameters)
       :headers-schema (headers-schema definitions parameters)
       :response-schemas (response-schemas definitions (:responses swagger-definition))
       :produces (some :produces [swagger-definition swagger-map])
       :consumes (some :consumes [swagger-definition swagger-map])
       :summary (:summary swagger-definition)
       :swagger-definition swagger-definition
       ;; todo path constraints - required?
       ;; :path-constraints {:id "(\\d+)"},
       ;; {:in "path", :name "id", :description "", :required true, :type "string", :format "uuid"
       :route-name route-name})))

(defn swagger->handlers [swagger-json]
  (let [swagger-spec (keywordize-keys swagger-json)]
    (reduce-kv
     (fn [handlers url-pattern swagger-handlers]
       (into handlers (keep (partial ->handler
                                     swagger-spec
                                     (:parameters swagger-handlers)
                                     url-pattern)
                            swagger-handlers)))
     []
     (:paths swagger-spec))))
