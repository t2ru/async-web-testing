(ns async-web-testing.core
  (:import
    (java.security KeyStore)
    (org.apache.http.concurrent FutureCallback)
    (org.apache.http.client.methods HttpGet)
    (org.apache.http.config Registry RegistryBuilder)
    (org.apache.http.nio.reactor ConnectingIOReactor)
    (org.apache.http.nio.conn NoopIOSessionStrategy)
    (org.apache.http.nio.conn.ssl SSLIOSessionStrategy)
    (org.apache.http.conn.ssl
      SSLContexts SSLConnectionSocketFactory TrustSelfSignedStrategy)
    (org.apache.http.impl.nio.conn PoolingNHttpClientConnectionManager)
    (org.apache.http.impl.nio.client HttpAsyncClients CloseableHttpAsyncClient)
    (org.apache.http.impl.nio.reactor DefaultConnectingIOReactor IOReactorConfig))
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

(defn measure-pool
  ^PoolingNHttpClientConnectionManager
  [^ConnectingIOReactor ior ^Registry rb
   {:keys [on-request on-lease on-release on-fail on-cancel]}]
  (proxy [PoolingNHttpClientConnectionManager] [ior rb]
    (requestConnection [route state connTO leaseTO tunit cb]
      (on-request)
      (let [new-cb (reify FutureCallback
                     (completed [this r] (on-lease) (.completed cb r))
                     (failed [this ex] (on-fail) (.failed cb ex))
                     (cancelled [this] (on-cancel) (.cancelled cb)))]
        (proxy-super requestConnection
                     route state connTO leaseTO tunit new-cb)))
    (releaseConnection [conn state keepalive tunit]
      (on-release)
      (proxy-super releaseConnection conn state keepalive tunit))))

(defn pool [listener]
  (doto (measure-pool
          (DefaultConnectingIOReactor. IOReactorConfig/DEFAULT)
          (-> (RegistryBuilder/create)
              (.register "http" NoopIOSessionStrategy/INSTANCE)
              (.register "https" (ssl-nocheck-strategy))
              (.build))
          listener)
    (.setDefaultMaxPerRoute 2)
    (.setMaxTotal 10)))

(defn client ^CloseableHttpAsyncClient [listener]
  (-> (HttpAsyncClients/custom)
      (.setConnectionManager (pool listener))
      (.build)))

(defn -main [^String url]
  (let [result-ch (chan (dropping-buffer 10000))]
    (go
      (loop []
        (when-let [r (<! result-ch)]
          (prn (java.util.Date.) r)
          (recur))))
    (with-open [c (client
                    {:on-request (fn [] (>!! result-ch :req))
                     :on-lease (fn [] (>!! result-ch :lease))
                     :on-release (fn [] (>!! result-ch :release))
                     :on-fail (fn [] (>!! result-ch :fail))
                     :on-cancel (fn [] (>!! result-ch :cancel))})]
      (.start c)
      (>!! result-ch :start)
      (let [fut1 (.execute c (HttpGet. url) nil)
            fut2 (.execute c (HttpGet. url) nil)
            fut3 (.execute c (HttpGet. url) nil)
            fut4 (.execute c (HttpGet. url) nil)
            fut5 (.execute c (HttpGet. url) nil)
            fut6 (.execute c (HttpGet. url) nil)
            r1 (.get fut1)
            r2 (.get fut2)
            r3 (.get fut3)
            r4 (.get fut3)
            r5 (.get fut3)
            r6 (.get fut4)]
        (>!! result-ch r1)
        (>!! result-ch r2)
        (>!! result-ch r3)
        (>!! result-ch r4)
        (>!! result-ch r5)
        (>!! result-ch r6)
        )
      (>!! result-ch :finish)
      (close! result-ch))))

