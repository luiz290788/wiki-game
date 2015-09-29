(ns wiki-game.core
  (:require [org.httpkit.client :as http]
            [clojure.core.async :as a])
  (:use [net.cgrand.enlive-html]))

(defn parse-html
  [html]
  (html-resource (java.io.StringReader. html)))

(defn normalize-url
  [url]
  (let [hash-index (.indexOf url "#")]
    (.toLowerCase (if (>= hash-index 0)
                    (.substring url 0 hash-index)
                    url
                    ))
    )
  )

(defn extract-links
  [parsed-html]
  (map #(get (:attrs %) :href) (select parsed-html [(attr-starts :href "/wiki")])))


(defn get-body
  [url]
  (:body @(http/get (str "http://thewikigame.com" url) {:headers {"Cookie" "sessionid=jkpxxzzvbovx9xgcma2il0q1yueswc8n; "}})))

(defn get-body-async
  [url]
  (let [body-chan (a/chan)]
    (a/go
      (let [body (get-body url)]
        (if (not (nil? body))
          (a/>! body-chan body))
        (a/close! body-chan)))
    body-chan)
  )

(defn create-vertex
  [origin dest]
  {dest origin})

(defn links
  [url]
  (->> url
    (get-body)
    (parse-html)
    (extract-links)
    (set)
    (map #(conj [url] %)))
  )

(defn links-async
  [url]
  (let [output (a/chan)]
    (a/pipeline-blocking 1 output
                (comp (map parse-html)
                      (map extract-links)
                      (mapcat distinct)
                      (map normalize-url)
                      (filter #(< (.indexOf % ":") 0))
                      (map #(conj [url] %)))
                (get-body-async url))
    output))

(defn build-graph
  [start end]
  (let 
    [to-do (a/chan (a/buffer 2048))
     normalized-end (normalize-url end)]
    (a/>!! to-do [nil (normalize-url start)])
    (a/<!! (a/go-loop 
             [[from current-path] (a/<! to-do)
              links-graph {}]
             (if (nil? to-do)
               nil
               (if (contains? links-graph normalized-end)
                 links-graph
                 (if (contains? links-graph current-path)
                   (recur (a/<! to-do) links-graph)
                   (let [links-chan (links-async current-path)]
                     (a/pipe links-chan to-do false)
                     (if (not (nil? from))
                       (recur (a/<! to-do) (assoc links-graph current-path from))
                       (recur (a/<! to-do) links-graph))))))))))


(defn wikipedia-min-path
  [start end]
  (loop [current-step (normalize-url end)
         path-head (normalize-url start)
         path []
         graph (build-graph start end)]
    (if (= current-step path-head)
      (cons current-step path)
      (recur (get graph current-step)
             path-head
             (cons current-step path)
             graph)
      )
    )
  )