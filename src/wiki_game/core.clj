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
    (a/pipeline 10 output
                (comp (map parse-html)
                      (map extract-links)
                      (mapcat distinct)
                      (map normalize-url)
                      (filter #(< (.indexOf % ":") 0))
                      (map #(conj [url] %)))
                (get-body-async url))
    output))

(defn level-links
  [sources]
  (a/merge (map #(links-async %) sources)))

(defn next-level-graph-and-links
  [links]
  (a/go-loop
        [current-level-graph {}
         next-level-links []
         links-chan (level-links links)
         c-link (a/<! links-chan)]
        (if (nil? c-link)
          [current-level-graph next-level-links]
          (recur (assoc current-level-graph (second c-link) (first c-link))
                 (conj next-level-links (second c-link))
                 links-chan
                 (a/<! links-chan)))
        ))

(defn build-graph
  [start end]
  (let 
    [to-do (a/chan (a/buffer 2048))
     normalized-end (normalize-url end)]
    (a/>!! to-do [nil (normalize-url start)])
    (a/<!! (a/go-loop 
             [links-graph {}
              level-links [(normalize-url start)]
              next-level-chan (next-level-graph-and-links level-links)]
             (let [next-level (a/<! next-level-chan)]
               (if (not (nil? next-level))
                 (let [merged-graph (merge (first next-level) links-graph)
                       nll (filter #(not (contains? links-graph %)) (distinct (second next-level)))]
                   (println (count nll))
                   (if (contains? merged-graph normalized-end)
                     merged-graph
                     (recur merged-graph
                            nll
                            (next-level-graph-and-links (second next-level)))))))
             ))
    ))


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