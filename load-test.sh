#!/bin/bash

# Configuration
URL="http://localhost:8080/api/notifications"
REQUEST_COUNT=10
CONCURRENT=false

echo "============================================="
echo " Starting Load Test for NotificationDispatcher"
echo " Target URL: $URL"
echo " Total Requests: $REQUEST_COUNT"
echo "============================================="

# Array of payloads to simulate different behaviors (success, validation error, unknown vendor, custom HTTP methods)
PAYLOADS=(
  '{"vendorId":"ad-system","httpMethod":"PUT","url":"https://httpbin.org/put","body":"{\"event\":\"user_signup\",\"userId\":\"1001\"}"}'
  '{"vendorId":"crm-system","httpMethod":"PUT","url":"https://httpbin.org/put","body":"{\"event\":\"deal_closed\",\"amount\":\"50000\"}"}'
  '{"vendorId":"inventory-system","httpMethod":"PUT","url":"https://httpbin.org/put","body":"{\"event\":\"stock_alert\",\"itemId\":\"item_404\"}"}'
  '{"vendorId":"invalid-vendor","body":"{\"event\":\"test\"}"}' # Unknown vendor -> 400 Bad Request
  '{"vendorId":"ad-system"}' # Missing body -> 400 Bad Request
  '{"vendorId":"ad-system","httpMethod":"PUT","url":"https://httpbin.org/put","body":"{\"event\":\"user_signup_put\",\"userId\":\"1002\"}"}' # PUT request override
  '{"vendorId":"ad-system","url":"https://this-is-a-fake-domain.com/api/callback","body":"{\"event\":\"failed_event_to_dlq\"}"}' # Fail -> Retry -> DLQ
)

# Function to send a single request
send_request() {
  local index=$1
  # Pick a payload from the list
  local payload_index=$((index % ${#PAYLOADS[@]}))
  local payload=${PAYLOADS[$payload_index]}
  
  echo "Sending request #$index..."
  response=$(curl -s -w "\nHTTP_STATUS:%{http_code}\n" -X POST "$URL" \
    -H "Content-Type: application/json" \
    -d "$payload")
  
  status_code=$(echo "$response" | grep "HTTP_STATUS" | cut -d':' -f2)
  body=$(echo "$response" | sed '/HTTP_STATUS/d')
  
  echo "Request #$index Result -> Status: $status_code, Body: $body"
  echo "---------------------------------------------"
}

# Run the test
if [ "$CONCURRENT" = true ]; then
  echo "Running in CONCURRENT mode..."
  for i in $(seq 1 $REQUEST_COUNT); do
    send_request $i &
  done
  wait
else
  echo "Running in SEQUENTIAL mode..."
  for i in $(seq 1 $REQUEST_COUNT); do
    send_request $i
    sleep 0.2 # small delay between requests
  done
fi

echo "============================================="
echo " Load Test Completed Successfully!"
echo "============================================="
