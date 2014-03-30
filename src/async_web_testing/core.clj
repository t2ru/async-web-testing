(ns async-web-testing.core
  (:require [clojure.core.async :as a
             :refer [>! <! >!! <!! chan thread go timeout
                     dropping-buffer]]))

(defmacro wait [ms]
  `(<! (timeout ~ms)))

(defmacro all<!
  ([])
  ([ch & rchs]
   `(let [ch# ~ch
          rrs# (all<! ~@rchs)]
     (cons (<! ch#) rrs#))))

(defn make-http-client []
  (let [c (-> (org.apache.http.impl.nio.client.HttpAsyncClientBuilder/create)
              (.build))]
    c))


(defn exec-http [hc url k finish-ch result-ch]
  (>!! result-ch {:time (java.util.Date.) :key k :start true :thread (Thread/currentThread)})
  (.execute
    hc
    (org.apache.http.nio.client.methods.HttpAsyncMethods/createGet url)
    (proxy [org.apache.http.nio.client.methods.AsyncCharConsumer] []
      (onResponseReceived [response]
        (>!! result-ch {:time (java.util.Date.) :key k :response nil}))
      (onCharReceived [buf ioctl]
        (let [len (.length buf)
              bs (char-array len)]
          (.get buf bs)
          (>!! result-ch {:time (java.util.Date.) :key k
                          ;:str (String. bs)
                          :len len})))
      (buildResult [context]
        (>!! result-ch {:time (java.util.Date.) :key k :status :result
                        :context (.toString context)})))
    (proxy [org.apache.http.concurrent.FutureCallback] []
      (completed [result]
        (>!! result-ch {:time (java.util.Date.) :key k :status :completed
                        :result result})
        (>!! finish-ch result))
      (failed [ex]
        (>!! result-ch {:time (java.util.Date.) :key k :status :failed
                        :error ex})
        (>!! finish-ch ex))
      (cancelled []
        (>!! result-ch {:time (java.util.Date.) :key k :status :cancelled})
        (>!! finish-ch ::cancelled)))))

(defn do-http [hc url k result-ch]
  (a/go
    (let [ch (chan)]
      (thread (exec-http hc url k ch result-ch))
      (<! ch))))

(defn hoge []
  (let [result-ch (chan (dropping-buffer 1000000))]
    (dotimes [i 5]
      (a/go
        (with-open [http-client (make-http-client)]
          (<! (thread (.start http-client)))
          (let [user-state (atom {})]
            ;(wait i)
            (all<!
              (do-http http-client "http://dev.taka2ru.jp/hod" :a result-ch)
              (do-http http-client "http://dev.taka2ru.jp/" :b result-ch)
              (do-http http-client "http://dev.taka2ru.jp/" :c result-ch)
              (do-http http-client "http://dev.taka2ru.jp/" :d result-ch))
            (wait 100)
            (all<!
              (do-http http-client "http://dev.taka2ru.jp/" :e result-ch)
              (do-http http-client "http://dev.taka2ru.jp/" :f result-ch)
              (do-http http-client "http://dev.taka2ru.jp/" :g result-ch)
              (do-http http-client "http://dev.taka2ru.jp/" :h result-ch))
            ;(wait 1000)
            (thread (>!! result-ch @user-state))
            ))))
    (with-open [w (clojure.java.io/writer (java.io.File. "hoge.log"))]
      (loop [i 1]
        (when-let [r (<!! result-ch)]
          (binding [*out* w]
            (prn i r))
          (recur (inc i)))))))

