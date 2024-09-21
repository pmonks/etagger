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

(ns urlocal.api-test
  (:require [urlocal.impl.cache :as uic]
            [clojure.test       :refer [deftest testing is]]
            [urlocal.api        :refer [set-cache-name! reset-cache! cache-check-interval-secs set-cache-check-interval-secs! input-stream]]))

; Make sure we reset (delete) the cache before we run the tests
(set-cache-name! "urlocal-tests")
(reset-cache!)

(defn valid-cached-response?
  [url input-strm]
  (and (not (nil? input-strm))
       (instance? java.io.InputStream input-strm)
       (.exists (uic/url->content-file  url))
       (.exists (uic/url->metadata-file url))))

(deftest input-stream-tests
  (testing "nil, blank, etc."
    (is (nil?                                   (input-stream nil)))
    (is (thrown? java.net.MalformedURLException (input-stream ""))))
  (testing "Invalid URLs"
    (is (thrown? java.io.IOException            (input-stream "http://INVALID_HOST_THAT_DOES_NOT_EXIST.local/"))))
  (testing "Valid URLs - cache miss"
    (is (valid-cached-response? "https://spdx.org/licenses/licenses.json" (input-stream "https://spdx.org/licenses/licenses.json"))))
  (testing "Valid URLs - cache hit & content fidelity"
    (is (= (slurp "https://spdx.org/licenses/licenses.json") (slurp (input-stream "https://spdx.org/licenses/licenses.json")))))  ; This URL must have previously been cached
  (testing "Valid URLs - within cache interval period"
    (is (valid-cached-response? "https://spdx.org/licenses/exceptions.json" (input-stream "https://spdx.org/licenses/exceptions.json"))))
  (testing "Valid URLs - outside cache interval period, but cache hit"
    (let [cache-check-interval (cache-check-interval-secs)]
      (set-cache-check-interval-secs! 0)
      (Thread/sleep 1000)  ; Make sure we have at least a once second gap between cache checks
      (is (valid-cached-response? "https://spdx.org/licenses/equivalentwords.txt" (input-stream "https://spdx.org/licenses/equivalentwords.txt")))
      (set-cache-check-interval-secs! cache-check-interval)))
  (testing "Redirected requests"
    (is (valid-cached-response? "https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt" (input-stream "https://www.gnu.org/licenses/gpl-2.0.txt" {:follow-redirects? true}))))
  (testing "Throttled requests"
    ; At times, gnu.org has throttled requests for license texts. Sadly this behaviour seems to change randomly, so this unit test is not guaranteed to actually test throttling behaviour at all times.
    (is (valid-cached-response? "https://www.gnu.org/licenses/gpl-3.0.txt" (input-stream "https://www.gnu.org/licenses/gpl-3.0.txt" {:retry-when-throttled? true})))))
