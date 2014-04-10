(ns async-web-testing.core
  (:import
    (java.security KeyStore)
    (org.apache.http.client.methods HttpGet)
    (org.apache.http.config RegistryBuilder)
    (org.apache.http.nio.conn NoopIOSessionStrategy)
    (org.apache.http.nio.conn.ssl SSLIOSessionStrategy)
    (org.apache.http.conn.ssl
      SSLContexts SSLConnectionSocketFactory TrustSelfSignedStrategy)
    (org.apache.http.impl.nio.conn PoolingNHttpClientConnectionManager)
    (org.apache.http.impl.nio.client HttpAsyncClients CloseableHttpAsyncClient)
    (org.apache.http.impl.nio.reactor
      DefaultConnectingIOReactor IOReactorConfig))
  (:require [clojure.core.async :as a
             :refer [>! <! >!! <!! chan thread go timeout close!
                     dropping-buffer]]))

(defmacro <!? [ch]
  `(let [r# (<! ~ch)]
     (if (instance? Throwable r#)
       (throw r#)
       r#)))

(defn ssl-nocheck-strategy []
  (SSLIOSessionStrategy.
    (SSLContexts/createSystemDefault)
    nil ;; supportedProtocols
    nil ;; supportedCipherSuites
    SSLIOSessionStrategy/ALLOW_ALL_HOSTNAME_VERIFIER))

(defn pool []
  (doto (PoolingNHttpClientConnectionManager.
          (DefaultConnectingIOReactor. IOReactorConfig/DEFAULT)
          (-> (RegistryBuilder/create)
              (.register "http" NoopIOSessionStrategy/INSTANCE)
              (.register "https" (ssl-nocheck-strategy))
              (.build)))
    (.setDefaultMaxPerRoute 2)
    (.setMaxTotal 10)))

(defn client ^CloseableHttpAsyncClient []
  (-> (HttpAsyncClients/custom)
      (.setConnectionManager (pool))
      (.build)))

(defn -main [url]
  (with-open [c (client)]
    (.start c)
    (prn (.get (.execute c (HttpGet. url) nil)))))

