(ns async-web-testing.core
  (:require [clojure.core.async :as a
             :refer [>! <! >!! <!! chan thread go timeout close!
                     dropping-buffer]]))

(defmacro wait [ms]
  `(<! (timeout ~ms)))

(defn sync! [& chs]
  `(<! (a/map vector chs)))

(defn fut->ch [^io.netty.channel.ChannelFuture fut]
  (let [callback-channel (chan)]
    (.addListener
      fut
      (reify io.netty.util.concurrent.GenericFutureListener
        (operationComplete [_ fut]
          (>!! callback-channel fut))))
    callback-channel))

(defn bootstrap []
  (let [gr (io.netty.channel.nio.NioEventLoopGroup.)]
    (-> (io.netty.bootstrap.Bootstrap.)
        (.group gr)
        (.channel io.netty.channel.socket.nio.NioSocketChannel)
        (.handler
         (proxy [io.netty.channel.ChannelInitializer] []
           (initChannel [sock]
             ;; do nothing
             ))))))

(defn process-fut [^io.netty.util.concurrent.GenericFutureListener fut]
  (cond
    (.isSuccess fut)
    fut
    (.isCancelled fut)
    (throw (IllegalStateException. "cancelled."))
    :else
    (throw (IllegalStateException. "failed." (.cause fut)))))

(defn fc& [fut]
  (let [ch (chan)]
    (.addListener
      fut
      (reify io.netty.channel.ChannelFutureListener
        (operationComplete [_ fut]
          (cond
            (.isSuccess fut)
            (do
              (>!! ch (.channel fut))
              (close! ch))
            (.isCancelled fut)
            (do
              (>!! ch (java.util.concurrent.CancellationException.
                        "cancelled"))
              (close! ch))
            :else
            (do
              (>!! ch (.cause fut))
              (close! ch))))))
    ch))

(defmacro <!? [ch]
  `(let [r# (<! ~ch)]
     (if (instance? Throwable r#)
       (throw r#)
       r#)))


(defn hoge []
  (let [result-ch (chan (dropping-buffer 1000000))]
    (go
      (try
        (let [b (bootstrap)]
          (>! result-ch :start)
          (let [req (doto (io.netty.handler.codec.http.DefaultFullHttpRequest.
                            io.netty.handler.codec.http.HttpVersion/HTTP_1_1
                            io.netty.handler.codec.http.HttpMethod/GET
                            "/trac/private")
                      (-> (.headers) (.set "Host" "dev.taka2ru.jp"))
                      (-> (.headers) (.set "Connection" "close")))
                cf (.connect b "dev.taka2ru.jp" 80)
                ch (.channel cf)
                pipe (.pipeline ch)]
            (.addLast
              pipe "codec"
              (io.netty.handler.codec.http.HttpClientCodec.))
            (.addLast
              pipe "handler"
              (proxy [io.netty.channel.SimpleChannelInboundHandler] []
                (channelRead0 [ctx msg]
                  (>!! result-ch [:read msg]))))
            (<!? (fc& cf))
            (<!? (fc& (.writeAndFlush ch req)))
            (<! (timeout 1000))
            (prn ch))
          (>! result-ch :end))
        (catch Throwable e
          (.printStackTrace e)
          (throw e))
        (finally
          (close! result-ch))))
    
    (with-open [w (clojure.java.io/writer (java.io.File. "hoge.log"))]
      (prn (java.util.Date.))
      (loop [i 1]
        (when-let [r (<!! result-ch)]
          (when (instance? Throwable r)
            (.printStackTrace r))
          (prn i (java.util.Date.) r)
          (recur (inc i)))))))


