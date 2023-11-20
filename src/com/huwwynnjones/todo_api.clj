(ns com.huwwynnjones.todo-api
  (:require [io.pedestal.http :as http]
            [io.pedestal.http.route :as route]
            [io.pedestal.test :as test])
  (:gen-class))

(defn response [status body & {:as headers}]
  {:status status :body body :headers headers})

(def ok (partial response 200))
(def created (partial response 201))
(def accepted (partial response 202))

(defonce database (atom {}))

(def database-interceptor
  {:name :datbase-interceptor
   :enter
   (fn [context]
     (update context :request assoc :database @database))
   :leave
   (fn [context]
     (if-let [[op & args] (:tx-data context)]
       (do
         (apply swap! database op args)
         (assoc-in context [:request :database] @database))
       context))})

(defn make-list [nm]
  {:name nm
   :items {}})

(defn make-list-item [nm]
  {:name nm
   :done? false})

(def list-create
  {:name :list-create
   :enter
   (fn [context]
     (let [nm (get-in context [:request :query-params :name] "Unamed list")
           new-list (make-list nm)
           db-id (str (gensym "1"))
           url (route/url-for :list-view :params {:list-id db-id})]
       (assoc context
              :response (created new-list "Location" url)
              :tx-data [assoc db-id new-list])))})

(defn find-list-by-id [dbval db-id]
  (get dbval db-id))

(def list-view
  {:name :list-view
   :enter
   (fn [context]
     (let [db-id (get-in context [:request :path-params :list-id])
           the-list (when db-id
                      (find-list-by-id
                       (get-in context [:request :database])
                       db-id))]
       (cond-> context
         the-list (assoc :result the-list))))})

(defn find-list-items-by-ids [dbval list-id item-id]
  (get-in dbval [list-id :items item-id] nil))

(def entity-reader
  {:name :entity-reader
   :leave
   (fn [context]
     (if-let [item (:result context)]
       (assoc context :response (ok item))
       context))})

(def list-item-view
  {:name :list-item-view
   :leave
   (fn [context]
     (let [list-id (get-in context [:request :path-params :list-id])
           item-id (and list-id
                        (get-in context [:request :path-params :item-id]))
           item (and item-id
                     (find-list-items-by-ids (get-in context [:request :database]) list-id item-id))]
       (cond-> context
         item (assoc :result item))))})

(defn list-item-add [dbval list-id item-id new-item]
  (if (contains? dbval list-id)
    (assoc-in dbval [list-id :items item-id] new-item)
    dbval))

(def list-item-create
  {:name :list-item-create
   :enter
   (fn [context]
     (if-let [list-id (get-in context [:request :path-params :list-id])]
       (let [nm (get-in context [:request :query-params :name] "Unamed item")
             new-item (make-list-item nm)
             item-id (str (gensym "i"))]
         (-> context
             (assoc :tx-data [list-item-add list-id item-id new-item])
             (assoc-in [:request :path-params :item-id] item-id)))
       context))})

(def echo
  {:name :echo
   :enter
   (fn [context]
     (let [request (:request context)
           response (ok context)]
       (assoc context :response response)))})

(def routes
  (route/expand-routes
   #{["/todo" :post [database-interceptor list-create]]
     ["/todo" :get echo :route-name :list-query-form]
     ["/todo/:list-id" :get [entity-reader database-interceptor list-view]]
     ["/todo/:list-id" :post [entity-reader list-item-view database-interceptor list-item-create]]
     ["/todo/:list-id/:item-id" :get [entity-reader list-item-view database-interceptor]]
     ["/todo/:list-id/:item-id" :put echo :route-name :list-item-update]
     ["/todo/:list-id/:item-id" :delete echo :route-name :list-item-delete]}))

(def service-map
  {::http/routes routes
   ::http/type :jetty
   ::http/port 8080})

(defn start []
  (http/start (http/create-server service-map)))

(defonce server (atom nil))

(defn start-dev []
  (reset! server
          (http/start (http/create-server
                       (assoc service-map ::http/join? false)))))

(defn stop-dev []
  (http/stop @server))

(defn restart []
  (stop-dev)
  (start-dev))

(defn test-request [verb url]
  (io.pedestal.test/response-for (::http/service-fn @server) verb url))