;
; Copyright © 2023 Peter Monks
;
; Licensed under the Apache License Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;     http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.
;
; SPDX-License-Identifier: Apache-2.0
;

(ns urlocal.cache-impl-test
  (:require [clojure.java.io    :as io]
            [clojure.test       :refer [deftest testing is]]
            [urlocal.impl.cache :as ulic :refer [base64-encode http-get prep-cache!]]))

(deftest base64-encode-tests
  (testing "nil, blank, etc."
    (is (nil?     (base64-encode nil)))
    (is (= ""     (base64-encode "")))
    (is (= "ICA=" (base64-encode "  ")))
    (is (= "DQoJ" (base64-encode "\r\n\t"))))
  (testing "Some urls"
    (is (= "aHR0cHM6Ly93d3cuZ29vZ2xlLmNvbS8=" (base64-encode "https://www.google.com/")))))

(deftest http-get-tests
  (testing "nil"
    (is (nil? (http-get nil)))
    (is (nil? (http-get nil nil))))
  (testing "Invalid URLs"
    (is (thrown? java.io.IOException (http-get (io/as-url "http://INVALID_HOST_THAT_DOES_NOT_EXIST.local/")))))
  (testing "Valid URLs"
    (is (not (nil? (http-get (io/as-url "https://www.google.com/")))))))

(defn prep-and-check-url-was-cached?
  "Preps the cache for url, then returns true if all of the cache files for that
  URL exist."
  [url]
  (let [url-to-test-b64      (ulic/base64-encode url)
        cached-content-file  (io/file (str @ulic/cache-dir-a "/" url-to-test-b64 ".content"))
        cached-metadata-file (io/file (str @ulic/cache-dir-a "/" url-to-test-b64 ".metadata.edn"))
        _                    (prep-cache! url nil)]
    (and (.exists cached-content-file) (.exists cached-metadata-file))))

(deftest prep-cache!-tests
  (testing "nil"
    (is (nil? (prep-cache! nil nil))))
  (testing "Valid URLs"
    (is (true? (prep-and-check-url-was-cached? (io/as-url "https://raw.githubusercontent.com/spdx/license-list-data/main/template/Apache-2.0.template.txt"))))))  ; Note: need to make sure this URL hasn't been used in a previous test
