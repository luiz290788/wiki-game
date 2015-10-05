(ns wiki-game.core
  (:require [clojure.core.async :as a]
            [clj-http.client :as client])
  (:use [net.cgrand.enlive-html]))

(defn parse-html
  [html]
  (html-resource (java.io.StringReader. html)))

(defn normalize-url
  [url]
  (let [hash-index (.indexOf url "#")]
    (if (>= hash-index 0)
      (.substring url 0 hash-index)
      url)))

(defn extract-links
  [parsed-html]
  (map #(get (:attrs %) :href) (select parsed-html [(attr-starts :href "/wiki")])))


(defn get-body-async
  [url]
  (a/thread 
    (try (:body (client/get (str "http://thewikigame.com" url) 
                             {:headers {"Cookie" "sessionid=jkpxxzzvbovx9xgcma2il0q1yueswc8n;"}}))
      (catch Exception e nil))
    ))

(defn create-vertex
  [origin dest]
  {dest origin})

(defn links-async
  [url]
  (let [output (a/chan)]
    (a/pipeline 100 output
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
  (let 
    [dsource (distinct sources)
     level-chan (a/chan)
     ack (a/chan 200)
     requests-count (a/chan 200)]
    (a/go-loop
      [c 0]
      (let 
        [fr (a/<! requests-count)
         plus-one (inc c)]
        (if (< plus-one (count dsource))
          (recur plus-one)
          (a/close! level-chan))))
    (a/go-loop 
      [q dsource]
      (if q
        (let 
          [signal (a/<! ack)]
          (if signal
            (let [] 
              (a/go
                (loop
                  [path-links (links-async (first q))
                   link (a/<! path-links)]
                  (if (not (nil? link))
                    (let []
                      (a/>! level-chan link)
                      (recur path-links (a/<! path-links)))
                    (let []
                      (a/>! requests-count true)
                      (a/>! ack true)))))
              (recur (next q)))))))
    (a/go-loop 
      [c 50]
      (if (> c 0)
        (let []
          (a/>! ack true)
          (recur (dec c)))))
    level-chan)
  )

(defn next-level-graph-and-links
  [links end]
  (a/go-loop
    [current-level-graph {}
     next-level-links []
     links-chan (level-links links)
     c-link (a/<! links-chan)]
    (if (nil? c-link)
      [current-level-graph next-level-links]
      (let [next-graph (assoc current-level-graph (second c-link) (first c-link))
            next-links (conj next-level-links (second c-link))]
        (if (= end (second c-link))
          [next-graph]
          (recur next-graph
                 next-links
                 links-chan
                 (a/<! links-chan))))
      )))

(defn build-graph
  [start end]
  (let 
    [to-do (a/chan (a/buffer 2048))
     normalized-end (normalize-url end)]
    (a/>!! to-do [nil (normalize-url start)])
    (a/<!! (a/go-loop 
             [links-graph {}
              level-links [(normalize-url start)]
              next-level-chan (next-level-graph-and-links level-links normalized-end)]
             (let [next-level (a/<! next-level-chan)]
               (if (not (nil? next-level))
                 (let [merged-graph (merge (first next-level) links-graph)
                       nll (filter #(not (contains? links-graph %)) (distinct (second next-level)))]
                   (if (contains? merged-graph normalized-end)
                     merged-graph
                     (recur merged-graph
                            nll
                            (next-level-graph-and-links (second next-level) normalized-end))))))
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