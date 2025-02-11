(ns puppetlabs.http.client.sync-plaintext-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer :all]
            [puppetlabs.http.client.common :as common]
            [puppetlabs.http.client.sync :as sync]
            [puppetlabs.http.client.test-common :refer :all]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as testutils]
            [puppetlabs.trapperkeeper.testutils.logging :as testlogging]
            [puppetlabs.trapperkeeper.testutils.webserver :as testwebserver]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [schema.test :as schema-test])
  (:import (com.puppetlabs.http.client ClientOptions HttpClientException RequestOptions
                                       ResponseBodyType SimpleRequestOptions
                                       Sync)
           (java.io ByteArrayInputStream InputStream)
           (java.net ConnectException ServerSocket SocketTimeoutException URI)
           (org.apache.http.impl.nio.client HttpAsyncClients)))

(use-fixtures :once schema-test/validate-schemas)

(defn app
  [_]
  {:status 200
   :body "Hello, World!"})

(defn cookie-handler
  [_]
  {:status 200
   :body "cookie has been set"
   :cookies {"session_id" {:value "session-id-hash"}}})

(defn check-cookie-handler
  [req]
  (if (empty? (get req :cookies))
    {:status 400
     :body "cookie has not been set"}
    {:status 200
     :body "cookie has been set"}))

(tk/defservice test-web-service
  [[:WebserverService add-ring-handler]]
  (init [this context]
        (add-ring-handler app "/hello")
        context))

(tk/defservice test-cookie-service
  [[:WebserverService add-ring-handler]]
  (init [this context]
        (add-ring-handler (wrap-cookies cookie-handler) "/cookietest")
        (add-ring-handler (wrap-cookies check-cookie-handler) "/cookiecheck")
        context))

(defn basic-test
  [http-method java-method clj-fn]
  (testing (format "sync client: HTTP method: '%s'" http-method)
    (testlogging/with-test-logging
      (testutils/with-app-with-config app
        [jetty9/jetty9-service test-web-service]
        {:webserver {:port 10000}}
        (testing "java sync client"
          (let [request-options (SimpleRequestOptions. (URI. "http://localhost:10000/hello/"))
                response (java-method request-options)]
            (is (= 200 (.getStatus response)))
            (is (= "OK" (.getReasonPhrase response)))
            (is (= "Hello, World!" (slurp (.getBody response))))))
        (testing "clojure sync client"
          (let [response (clj-fn "http://localhost:10000/hello/")]
            (is (= 200 (:status response)))
            (is (= "OK" (:reason-phrase response)))
            (is (= "Hello, World!" (slurp (:body response))))))))))

(deftest sync-client-head-test
  (testlogging/with-test-logging
    (testutils/with-app-with-config app
      [jetty9/jetty9-service test-web-service]
      {:webserver {:port 10000}}
      (testing "java sync client"
        (let [request-options (SimpleRequestOptions. (URI. "http://localhost:10000/hello/"))
              response (Sync/head request-options)]
          (is (= 200 (.getStatus response)))
          (is (= nil (.getBody response)))))
      (testing "clojure sync client"
        (let [response (sync/head "http://localhost:10000/hello/")]
          (is (= 200 (:status response)))
          (is (= nil (:body response)))))
      (testing "not found"
        (let [response (sync/head "http://localhost:10000/missing")]
          (is (= 404 (:status response)))
          (is (= "Not Found" (:reason-phrase response))))))))

(deftest sync-client-get-test
  (basic-test "GET" #(Sync/get %) sync/get))

(deftest sync-client-post-test
  (basic-test "POST" #(Sync/post %) sync/post))

(deftest sync-client-put-test
  (basic-test "PUT" #(Sync/put %) sync/put))

(deftest sync-client-delete-test
  (basic-test "DELETE" #(Sync/delete %) sync/delete))

(deftest sync-client-trace-test
  (basic-test "TRACE" #(Sync/trace %) sync/trace))

(deftest sync-client-options-test
  (basic-test "OPTIONS" #(Sync/options %) sync/options))

(deftest sync-client-patch-test
  (basic-test "PATCH" #(Sync/patch %) sync/patch))

(deftest sync-client-persistent-test
  (testlogging/with-test-logging
    (testutils/with-app-with-config app
      [jetty9/jetty9-service test-web-service]
      {:webserver {:port 10000}}
      (testing "persistent java client"
        (let [request-options (RequestOptions. "http://localhost:10000/hello/")
              client-options (ClientOptions.)
              client (Sync/createClient client-options)]
          (testing "HEAD request with persistent sync client"
            (let [response (.head client request-options)]
              (is (= 200 (.getStatus response)))
              (is (= nil (.getBody response)))))
          (testing "GET request with persistent sync client"
            (let [response (.get client request-options)]
              (is (= 200 (.getStatus response)))
              (is (= "Hello, World!" (slurp (.getBody response))))))
          (testing "POST request with persistent sync client"
            (let [response (.post client request-options)]
              (is (= 200 (.getStatus response)))
              (is (= "Hello, World!" (slurp (.getBody response))))))
          (testing "PUT request with persistent sync client"
            (let [response (.put client request-options)]
              (is (= 200 (.getStatus response)))
              (is (= "Hello, World!" (slurp (.getBody response))))))
          (testing "DELETE request with persistent sync client"
            (let [response (.delete client request-options)]
              (is (= 200 (.getStatus response)))
              (is (= "Hello, World!" (slurp (.getBody response))))))
          (testing "TRACE request with persistent sync client"
            (let [response (.trace client request-options)]
              (is (= 200 (.getStatus response)))
              (is (= "Hello, World!" (slurp (.getBody response))))))
          (testing "OPTIONS request with persistent sync client"
            (let [response (.options client request-options)]
              (is (= 200 (.getStatus response)))
              (is (= "Hello, World!" (slurp (.getBody response))))))
          (testing "PATCH request with persistent sync client"
            (let [response (.patch client request-options)]
              (is (= 200 (.getStatus response)))
              (is (= "Hello, World!" (slurp (.getBody response))))))
          (testing "client closes properly"
            (.close client)
            (is (thrown? HttpClientException
                         (.get client request-options))))))
      (testing "persistent clojure client"
        (let [client (sync/create-client {})]
          (testing "HEAD request with persistent sync client"
            (let [response (common/head client "http://localhost:10000/hello/")]
              (is (= 200 (:status response)))
              (is (= nil (:body response)))))
          (testing "GET request with persistent sync client"
            (let [response (common/get client "http://localhost:10000/hello/")]
              (is (= 200 (:status response)))
              (is (= "Hello, World!" (slurp (:body response))))))
          (testing "POST request with persistent sync client"
            (let [response (common/post client "http://localhost:10000/hello/")]
              (is (= 200 (:status response)))
              (is (= "Hello, World!" (slurp (:body response))))))
          (testing "PUT request with persistent sync client"
            (let [response (common/put client "http://localhost:10000/hello/")]
              (is (= 200 (:status response)))
              (is (= "Hello, World!" (slurp (:body response))))))
          (testing "DELETE request with persistent sync client"
            (let [response (common/delete client "http://localhost:10000/hello/")]
              (is (= 200 (:status response)))
              (is (= "Hello, World!" (slurp (:body response))))))
          (testing "TRACE request with persistent sync client"
            (let [response (common/trace client "http://localhost:10000/hello/")]
              (is (= 200 (:status response)))
              (is (= "Hello, World!" (slurp (:body response))))))
          (testing "OPTIONS request with persistent sync client"
            (let [response (common/options client "http://localhost:10000/hello/")]
              (is (= 200 (:status response)))
              (is (= "Hello, World!" (slurp (:body response))))))
          (testing "PATCH request with persistent sync client"
            (let [response (common/patch client "http://localhost:10000/hello/")]
              (is (= 200 (:status response)))
              (is (= "Hello, World!" (slurp (:body response))))))
          (testing "GET request via request function with persistent sync client"
            (let [response (common/make-request client "http://localhost:10000/hello/" :get)]
              (is (= 200 (:status response)))
              (is (= "Hello, World!" (slurp (:body response))))))
          (testing "Bad verb request via request function with persistent sync client"
            (is (thrown? IllegalArgumentException
                         (common/make-request client
                                              "http://localhost:10000/hello/"
                                              :bad))))
          (testing "client closes properly"
            (common/close client)
            (is (thrown? IllegalStateException
                         (common/get client
                                     "http://localhost:10000/hello")))))))))

(deftest sync-client-as-test
  (testlogging/with-test-logging
    (testutils/with-app-with-config app
      [jetty9/jetty9-service test-web-service]
      {:webserver {:port 10000}}
      (testing "java sync client: :as unspecified"
        (let [request-options (SimpleRequestOptions. (URI. "http://localhost:10000/hello/"))
              response (Sync/get request-options)]
          (is (= 200 (.getStatus response)))
          (is (instance? InputStream (.getBody response)))
          (is (= "Hello, World!" (slurp (.getBody response))))))
      (testing "java sync client: :as :stream"
        (let [request-options (.. (SimpleRequestOptions. (URI. "http://localhost:10000/hello/"))
                                  (setAs ResponseBodyType/STREAM))
              response (Sync/get request-options)]
          (is (= 200 (.getStatus response)))
          (is (instance? InputStream (.getBody response)))
          (is (= "Hello, World!" (slurp (.getBody response))))))
      (testing "java sync client: :as :text"
        (let [request-options (.. (SimpleRequestOptions. (URI. "http://localhost:10000/hello/"))
                                  (setAs ResponseBodyType/TEXT))
              response (Sync/get request-options)]
          (is (= 200 (.getStatus response)))
          (is (string? (.getBody response)))
          (is (= "Hello, World!" (.getBody response)))))
      (testing "clojure sync client: :as unspecified"
        (let [response (sync/get "http://localhost:10000/hello/")]
          (is (= 200 (:status response)))
          (is (instance? InputStream (:body response)))
          (is (= "Hello, World!" (slurp (:body response))))))
      (testing "clojure sync client: :as :stream"
        (let [response (sync/get "http://localhost:10000/hello/" {:as :stream})]
          (is (= 200 (:status response)))
          (is (instance? InputStream (:body response)))
          (is (= "Hello, World!" (slurp (:body response))))))
      (testing "clojure sync client: :as :text"
        (let [response (sync/get "http://localhost:10000/hello/" {:as :text})]
          (is (= 200 (:status response)))
          (is (string? (:body response)))
          (is (= "Hello, World!" (:body response))))))))

(deftest request-with-client-test
  (testlogging/with-test-logging
    (testutils/with-app-with-config app
      [jetty9/jetty9-service test-web-service]
      {:webserver {:port 10000}}
      (let [client (HttpAsyncClients/createDefault)
            opts   {:method :get :url "http://localhost:10000/hello/"}]
        (.start client)
        (testing "GET request works with request-with-client"
          (let [response (sync/request-with-client opts client)]
            (is (= 200 (:status response)))
            (is (= "Hello, World!" (slurp (:body response))))))
        (testing "Client persists when passed to request-with-client"
          (let [response (sync/request-with-client opts client)]
            (is (= 200 (:status response)))
            (is (= "Hello, World!" (slurp (:body response))))))
        (.close client)))))

(deftest java-api-cookie-test
  (testlogging/with-test-logging
    (testutils/with-app-with-config app
      [jetty9/jetty9-service test-cookie-service]
      {:webserver {:port 10000}}
      (let [client (Sync/createClient (ClientOptions.))]
        (testing "Set a cookie using Java API"
          (let [response (.get client (RequestOptions. "http://localhost:10000/cookietest"))]
            (is (= 200 (.getStatus response)))))
        (testing "Check if cookie still exists"
          (let [response (.get client (RequestOptions. "http://localhost:10000/cookiecheck"))]
            (is (= 200 (.getStatus response)))))))))

(deftest clj-api-cookie-test
  (testlogging/with-test-logging
    (testutils/with-app-with-config app
      [jetty9/jetty9-service test-cookie-service]
      {:webserver {:port 10000}}
      (let [client (sync/create-client {})]
        (testing "Set a cookie using Clojure API"
          (let [response (common/get client "http://localhost:10000/cookietest")]
            (is (= 200 (:status response)))))
        (testing "Check if cookie still exists"
          (let [response (common/get client "http://localhost:10000/cookiecheck")]
            (is (= 200 (:status response)))))))))

(defn header-app
  [req]
  (let [val (get-in req [:headers "fooheader"])]
    {:status  200
     :headers {"myrespheader" val}
     :body    val}))

(tk/defservice test-header-web-service
  [[:WebserverService add-ring-handler]]
  (init [this context]
       (add-ring-handler header-app "/hello")
       context))

(deftest sync-client-request-headers-test
  (testlogging/with-test-logging
    (testutils/with-app-with-config header-app
      [jetty9/jetty9-service test-header-web-service]
      {:webserver {:port 10000}}
      (testing "java sync client"
        (let [request-options (-> (SimpleRequestOptions. (URI. "http://localhost:10000/hello/"))
                                  (.setHeaders {"fooheader" "foo"}))
              response (Sync/post request-options)]
          (is (= 200 (.getStatus response)))
          (is (= "foo" (slurp (.getBody response))))
          (is (= "foo" (-> (.getHeaders response) (.get "myrespheader"))))))
      (testing "clojure sync client"
        (let [response (sync/post "http://localhost:10000/hello/" {:headers {"fooheader" "foo"}})]
          (is (= 200 (:status response)))
          (is (= "foo" (slurp (:body response))))
          (is (= "foo" (get-in response [:headers "myrespheader"]))))))))

(defn req-body-app
  [req]
  {:status  200
   :headers (when-let [content-type (:content-type req)]
              {"Content-Type" content-type})
   :body    (slurp (:body req))})

(tk/defservice test-body-web-service
               [[:WebserverService add-ring-handler]]
               (init [this context]
                     (add-ring-handler req-body-app "/hello")
                     context))
(defn- validate-java-request
  [body-to-send headers-to-send expected-content-type expected-response-body]
  (let [request-options (-> (SimpleRequestOptions. (URI. "http://localhost:10000/hello/"))
                            (.setBody body-to-send)
                            (.setHeaders headers-to-send))
        response (Sync/post request-options)]
    (is (= 200 (.getStatus response)))
    (is (= (-> (.getHeaders response)
               (.get "content-type"))
           expected-content-type))
    (is (= expected-response-body (slurp (.getBody response))))))

(defn- validate-clj-request
  [body-to-send headers-to-send expected-content-type expected-response-body]
  (let [response (sync/post "http://localhost:10000/hello/"
                            {:body body-to-send
                             :headers headers-to-send})]
    (is (= 200 (:status response)))
    (is (= (get-in response [:headers "content-type"])
           expected-content-type))
    (is (= expected-response-body (slurp (:body response))))))

(deftest sync-client-request-body-test
  (testlogging/with-test-logging
    (testutils/with-app-with-config req-body-app
      [jetty9/jetty9-service test-body-web-service]
      {:webserver {:port 10000}}
      (testing "java sync client: string body for post request with explicit
                content type and UTF-8 encoding uses UTF-8 encoding"
        (validate-java-request "foo�"
                               {"Content-Type" "text/plain; charset=utf-8"}
                               "text/plain;charset=utf-8"
                               "foo�"))
      (testing "java sync client: string body for post request with explicit
                content type and ISO-8859-1 encoding uses ISO-8859-1 encoding"
        (validate-java-request "foo�"
                               {"Content-Type" "text/plain; charset=iso-8859-1"}
                               "text/plain;charset=iso-8859-1"
                               "foo?"))
      (testing "java sync client: string body for post request with explicit
                content type but without explicit encoding uses UTF-8 encoding"
        (validate-java-request "foo�"
                               {"Content-Type" "text/plain"}
                               "text/plain;charset=utf-8"
                               "foo�"))
      (testing "java sync client: string body for post request without explicit
                content or encoding uses ISO-8859-1 encoding"
        (validate-java-request "foo�"
                               nil
                               "text/plain;charset=iso-8859-1"
                               "foo?"))
      (testing "java sync client: input stream body for post request"
        (let [request-options (-> (SimpleRequestOptions. (URI. "http://localhost:10000/hello/"))
                                  (.setBody (ByteArrayInputStream.
                                              (.getBytes "foo�" "UTF-8")))
                                  (.setHeaders {"Content-Type"
                                                "text/plain; charset=UTF-8"}))
              response (Sync/post request-options)]
          (is (= 200 (.getStatus response)))
          (is (= "foo�" (slurp (.getBody response))))))
      (testing "clojure sync client: string body for post request with explicit
                content type and UTF-8 encoding uses UTF-8 encoding"
        (validate-clj-request "foo�"
                              {"content-type" "text/plain; charset=utf-8"}
                              "text/plain;charset=utf-8"
                              "foo�"))
      (testing "clojure sync client: string body for post request with explicit
                content type and ISO-8859 encoding uses ISO-8859-1 encoding"
        (validate-clj-request "foo�"
                              {"content-type" "text/plain; charset=iso-8859-1"}
                              "text/plain;charset=iso-8859-1"
                              "foo?"))
      (testing "clojure sync client: string body for post request with explicit
                content type but without explicit encoding uses UTF-8 encoding"
        (validate-clj-request "foo�"
                              {"content-type" "text/plain"}
                              "text/plain;charset=utf-8"
                              "foo�"))
      (testing "clojure sync client: string body for post request without explicit
                content type or encoding uses ISO-8859-1 encoding"
        (validate-clj-request "foo�"
                              {}
                              "text/plain;charset=iso-8859-1"
                              "foo?"))
      (testing "clojure sync client: input stream body for post request"
        (let [response (sync/post "http://localhost:10000/hello/"
                                  {:body    (io/input-stream
                                             (.getBytes "foo�" "UTF-8"))
                                   :headers {"content-type"
                                             "text/plain; charset=UTF-8"}})]
          (is (= 200 (:status response)))
          (is (= "foo�" (slurp (:body response)))))))))

(def compressible-body (apply str (repeat 1000 "f")))

(defn compression-app
  [req]
  {:status  200
   :headers {"orig-accept-encoding" (get-in req [:headers "accept-encoding"])
             "content-type" "text/plain"
             "charset" "UTF-8"}
   :body    compressible-body})

(tk/defservice test-compression-web-service
  [[:WebserverService add-ring-handler]]
  (init [this context]
       (add-ring-handler compression-app "/hello")
       context))

(defn test-compression
  [desc opts accept-encoding content-encoding content-should-match?]
  (testlogging/with-test-logging
    (testutils/with-app-with-config req-body-app
      [jetty9/jetty9-service test-compression-web-service]
      {:webserver {:port 10000}}
      (testing (str "java sync client: compression headers / response: " desc)
        (let [request-opts (cond-> (SimpleRequestOptions. (URI. "http://localhost:10000/hello/"))
                                   (contains? opts :decompress-body) (.setDecompressBody (:decompress-body opts))
                                   (contains? opts :headers) (.setHeaders (:headers opts)))
              response (Sync/get request-opts)]
          (is (= 200 (.getStatus response)))
          (is (= accept-encoding (.. response getHeaders (get "orig-accept-encoding"))))
          (is (= content-encoding (.. response getOrigContentEncoding)))
          (if content-should-match?
            (is (= compressible-body (slurp (.getBody response))))
            (is (not= compressible-body (slurp (.getBody response)))))))
      (testing (str "clojure sync client: compression headers / response: " desc)
        (let [response (sync/post "http://localhost:10000/hello/" opts)]
          (is (= 200 (:status response)))
          (is (= accept-encoding (get-in response [:headers "orig-accept-encoding"])))
          (is (= content-encoding (:orig-content-encoding response)))
          (if content-should-match?
            (is (= compressible-body (slurp (:body response))))
            (is (not= compressible-body (slurp (:body response))))))))))

(deftest sync-client-compression-test
  (test-compression "default" {} "gzip, deflate" "gzip" true))

(deftest sync-client-compression-gzip-test
  (test-compression "explicit gzip" {:headers {"accept-encoding" "gzip"}} "gzip" "gzip" true))

(deftest sync-client-compression-disabled-test
  (test-compression "explicit disable" {:decompress-body false} nil nil true))

(deftest sync-client-decompression-disabled-test
  (test-compression "explicit disable" {:headers {"accept-encoding" "gzip"}
                                        :decompress-body false} "gzip" "gzip" false))

(deftest query-params-test-sync
  (testlogging/with-test-logging
    (testutils/with-app-with-config app
      [jetty9/jetty9-service test-params-web-service]
      {:webserver {:port 8080}}
      (testing "URL Query Parameters work with the Java client"
        (let [request-options (SimpleRequestOptions. (URI. "http://localhost:8080/params?foo=bar&baz=lux"))
              response (Sync/get request-options)]
            (is (= 200 (.getStatus response)))
            (is (= queryparams (read-string (slurp (.getBody response)))))))

      (testing "URL Query Parameters work with the clojure client"
        (let [opts {:method       :get
                    :url          "http://localhost:8080/params/"
                    :query-params queryparams
                    :as           :text}
              response (sync/get "http://localhost:8080/params" opts)]
          (is (= 200 (:status response)))
          (is (= queryparams (read-string (:body response)))))))))

(deftest redirect-test-sync
  (testlogging/with-test-logging
    (testutils/with-app-with-config app
      [jetty9/jetty9-service redirect-web-service]
      {:webserver {:port 8080}}
      (testing (str "redirects on POST not followed by Java client "
                    "when forceRedirects option not set to true")
        (let [request-options  (SimpleRequestOptions. (URI. "http://localhost:8080/hello"))
              response (Sync/post request-options)]
          (is (= 302 (.getStatus response)))))
      (testing "redirects on POST followed by Java client when option is set"
        (let [request-options (.. (SimpleRequestOptions. (URI. "http://localhost:8080/hello"))
                                  (setForceRedirects true))
              response (Sync/post request-options)]
          (is (= 200 (.getStatus response)))
          (is (= "Hello, World!" (slurp (.getBody response))))))
      (testing "redirects not followed by Java client when :follow-redirects is false"
        (let [request-options (.. (SimpleRequestOptions. (URI. "http://localhost:8080/hello"))
                                  (setFollowRedirects false))
              response (Sync/get request-options)]
          (is (= 302 (.getStatus response)))))
      (testing ":follow-redirects overrides :force-redirects for Java client"
        (let [request-options (.. (SimpleRequestOptions. (URI. "http://localhost:8080/hello"))
                                  (setFollowRedirects false)
                                  (setForceRedirects true))
              response (Sync/get request-options)]
          (is (= 302 (.getStatus response)))))
      (testing (str "redirects on POST not followed by clojure client "
                    "when :force-redirects is not set to true")
        (let [opts     {:method           :post
                        :url              "http://localhost:8080/hello"
                        :as               :text
                        :force-redirects  false}
              response (sync/post "http://localhost:8080/hello" opts)]
          (is (= 302 (:status response)))))
      (testing "redirects on POST followed by clojure client when option is set"
        (let [opts     {:method           :post
                        :url              "http://localhost:8080/hello"
                        :as               :text
                        :force-redirects  true}
              response (sync/post "http://localhost:8080/hello" opts)]
          (is (= 200 (:status response)))
          (is (= "Hello, World!" (:body response)))))
      (testing (str "redirects not followed by clojure client when :follow-redirects "
                    "is set to false")
        (let [response (sync/get "http://localhost:8080/hello" {:as :text
                                                                 :follow-redirects false})]
          (is (= 302 (:status response)))))
      (testing ":follow-redirects overrides :force-redirects with clojure client"
        (let [response (sync/get "http://localhost:8080/hello" {:as :text
                                                                 :follow-redirects false
                                                                 :force-redirects true})]
          (is (= 302 (:status response)))))
      (testing (str "redirects on POST followed by persistent clojure client "
                    "when option is set")
        (let [client (sync/create-client {:force-redirects true})
              response (common/post client "http://localhost:8080/hello" {:as :text})]
          (is (= 200 (:status response)))
          (is (= "Hello, World!" (:body response)))
          (common/close client)))
      (testing (str "persistent clojure client does not follow redirects when "
                    ":follow-redirects is set to false")
        (let [client (sync/create-client {:follow-redirects false})
              response (common/get client "http://localhost:8080/hello" {:as :text})]
          (is (= 302 (:status response)))
          (common/close client)))
      (testing ":follow-redirects overrides :force-redirects with persistent clj client"
        (let [client (sync/create-client {:follow-redirects false
                                           :force-redirects true})
              response (common/get client "http://localhost:8080/hello" {:as :text})]
          (is (= 302 (:status response)))
          (common/close client))))))

(defmacro wrapped-connect-exception-thrown?
  [& body]
  `(try
     (testlogging/with-test-logging ~@body)
     (throw (IllegalStateException.
              "Expected HttpClientException but none thrown!"))
     (catch HttpClientException e#
       (if-let [cause# (.getCause e#)]
         (or (instance? SocketTimeoutException cause#)
             (instance? ConnectException cause#)
             (throw (IllegalStateException.
                      (str
                        "Expected SocketTimeoutException or ConnectException "
                        "cause but found: " cause#))))
         (throw (IllegalStateException.
                  (str
                    "Expected SocketTimeoutException or ConnectException but "
                    "no cause found.  Message:" (.getMessage e#))))))))

(defmacro wrapped-timeout-exception-thrown?
  [& body]
  `(try
     (testlogging/with-test-logging ~@body)
     (throw (IllegalStateException.
              "Expected HttpClientException but none thrown!"))
     (catch HttpClientException e#
       (if-let [cause# (.getCause e#)]
         (or (instance? SocketTimeoutException cause#)
             (throw (IllegalStateException.
                      (str
                        "Expected SocketTimeoutException cause but found: "
                        cause#))))
         (throw (IllegalStateException.
                  (str
                    "Expected SocketTimeoutException but no cause found.  "
                    "Message: " (.getMessage e#))))))))

(deftest short-connect-timeout-nonpersistent-java-test-sync
  (testing (str "connection times out properly for non-persistent java sync "
                "request with short timeout")
    (let [request-options     (-> "http://127.0.0.255:65535"
                                  (SimpleRequestOptions.)
                                  (.setConnectTimeoutMilliseconds 250))
          time-before-connect (System/currentTimeMillis)]
      (is (wrapped-connect-exception-thrown?
            (Sync/get request-options))
          "Unexpected result for connection attempt")
      (is (elapsed-within-range? time-before-connect 2000)
          "Connection attempt took significantly longer than timeout"))))

(deftest short-connect-timeout-persistent-java-test-sync
  (testing (str "connection times out properly for java persistent client sync "
                "request with short timeout")
    (with-open [client (-> (ClientOptions.)
                           (.setConnectTimeoutMilliseconds 250)
                           (Sync/createClient))]
      (let [request-options     (RequestOptions. "http://127.0.0.255:65535")
            time-before-connect (System/currentTimeMillis)]
        (is (wrapped-connect-exception-thrown?
              (.get client request-options))
            "Unexpected result for connection attempt")
        (is (elapsed-within-range? time-before-connect 2000)
            "Connection attempt took significantly longer than timeout")))))

(deftest short-connect-timeout-nonpersistent-clojure-test-sync
  (testing (str "connection times out properly for non-persistent clojure sync "
                "request with short timeout")
    (let [time-before-connect (System/currentTimeMillis)]
      (is (connect-exception-thrown?
            (sync/get "http://127.0.0.255:65535"
                      {:connect-timeout-milliseconds 250}))
          "Unexpected result for connection attempt")
      (is (elapsed-within-range? time-before-connect 2000)
          "Connection attempt took significantly longer than timeout"))))

(deftest short-connect-timeout-persistent-clojure-test-sync
  (testing (str "connection times out properly for clojure persistent client "
                "sync request with short timeout")
    (with-open [client (sync/create-client
                         {:connect-timeout-milliseconds 250})]
      (let [time-before-connect (System/currentTimeMillis)]
        (is (connect-exception-thrown?
              (common/get client "http://127.0.0.255:65535"))
            "Unexpected result for connection attempt")
        (is (elapsed-within-range? time-before-connect 2000)
            "Connection attempt took significantly longer than timeout")))))

(deftest longer-connect-timeout-test-sync
  (testing "connection succeeds for sync request with longer connect timeout"
    (testlogging/with-test-logging
      (testwebserver/with-test-webserver app port
        (let [url (str "http://localhost:" port "/hello")]
          (testing "java non-persistent sync client"
            (let [request-options (.. (SimpleRequestOptions. url)
                                      (setConnectTimeoutMilliseconds 2000))
                  response        (Sync/get request-options)]
              (is (= 200 (.getStatus response)))
              (is (= "Hello, World!" (slurp (.getBody response))))))
          (testing "java persistent sync client"
            (with-open [client (-> (ClientOptions.)
                                   (.setConnectTimeoutMilliseconds 2000)
                                   (Sync/createClient))]
              (let [response (.get client (RequestOptions. url))]
                (is (= 200 (.getStatus response)))
                (is (= "Hello, World!" (slurp (.getBody response)))))))
          (testing "clojure non-persistent sync client"
            (let [response (sync/get url
                                     {:as :text
                                      :connect-timeout-milliseconds 2000})]
              (is (= 200 (:status response)))
              (is (= "Hello, World!" (:body response)))))
          (testing "clojure persistent sync client"
            (with-open [client (sync/create-client
                                 {:connect-timeout-milliseconds 2000})]
              (let [response (common/get client url {:as :text})]
                (is (= 200 (:status response)))
                (is (= "Hello, World!" (:body response)))))))))))

(deftest short-socket-timeout-nonpersistent-java-test-sync
  (testing (str "socket read times out properly for non-persistent java sync "
                "request with short timeout")
    (with-open [server (ServerSocket. 0)]
      (let [request-options     (-> "http://127.0.0.1:"
                                    (str (.getLocalPort server))
                                    (SimpleRequestOptions.)
                                    (.setSocketTimeoutMilliseconds 250))
            time-before-connect (System/currentTimeMillis)]
        (is (wrapped-timeout-exception-thrown? (Sync/get request-options))
            "Unexpected result for get attempt")
        (is (elapsed-within-range? time-before-connect 2000)
            "Get attempt took significantly longer than timeout")))))

(deftest short-socket-timeout-persistent-java-test-sync
  (testing (str "socket read times out properly for persistent java sync "
                "request with short timeout")
    (with-open [client (-> (ClientOptions.)
                           (.setSocketTimeoutMilliseconds 250)
                           (Sync/createClient))
                server (ServerSocket. 0)]
      (let [request-options     (-> "http://127.0.0.1:"
                                    (str (.getLocalPort server))
                                    (RequestOptions.))
            time-before-connect (System/currentTimeMillis)]
        (is (wrapped-timeout-exception-thrown? (.get client request-options))
            "Unexpected result for get attempt")
        (is (elapsed-within-range? time-before-connect 2000)
            "Get attempt took significantly longer than timeout")))))

(deftest short-socket-timeout-nonpersistent-clojure-test-sync
  (testing (str "socket read times out properly for non-persistent clojure "
                "sync request with short timeout")
    (with-open [server (ServerSocket. 0)]
      (let [url                 (str "http://127.0.0.1:" (.getLocalPort server))
            time-before-connect (System/currentTimeMillis)]
        (is (thrown? SocketTimeoutException
                     (sync/get url {:socket-timeout-milliseconds 250}))
            "Unexpected result for get attempt")
        (is (elapsed-within-range? time-before-connect 2000)
            "Get attempt took significantly longer than timeout")))))

(deftest short-socket-timeout-persistent-clojure-test-sync
  (testing (str "socket read times out properly for clojure persistent client "
                "sync request with short timeout")
    (with-open [client (sync/create-client
                         {:socket-timeout-milliseconds 250})
                server (ServerSocket. 0)]
      (let [url                 (str "http://127.0.0.1:" (.getLocalPort server))
            time-before-connect (System/currentTimeMillis)]
        (is (thrown? SocketTimeoutException (common/get client url))
            "Unexpected result for get attempt")
        (is (elapsed-within-range? time-before-connect 2000)
            "Get attempt took significantly longer than timeout")))))

(deftest longer-socket-timeout-test-sync
  (testing "get succeeds for sync request with longer socket timeout"
    (testlogging/with-test-logging
      (testwebserver/with-test-webserver app port
        (let [url (str "http://localhost:" port "/hello")]
          (testing "java non-persistent sync client"
            (let [request-options (.. (SimpleRequestOptions. url)
                                      (setSocketTimeoutMilliseconds 2000))
                  response        (Sync/get request-options)]
              (is (= 200 (.getStatus response)))
              (is (= "Hello, World!" (slurp (.getBody response))))))
          (testing "java persistent sync client"
            (with-open [client (-> (ClientOptions.)
                                   (.setSocketTimeoutMilliseconds 2000)
                                   (Sync/createClient))]
              (let [response (.get client (RequestOptions. url))]
                (is (= 200 (.getStatus response)))
                (is (= "Hello, World!" (slurp (.getBody response)))))))
          (testing "clojure non-persistent sync client"
            (let [response (sync/get url
                                     {:as :text
                                      :socket-timeout-milliseconds 2000})]
              (is (= 200 (:status response)))
              (is (= "Hello, World!" (:body response)))))
          (testing "clojure persistent sync client"
            (with-open [client (sync/create-client
                                 {:socket-timeout-milliseconds 2000})]
              (let [response (common/get client url {:as :text})]
                (is (= 200 (:status response)))
                (is (= "Hello, World!" (:body response)))))))))))
