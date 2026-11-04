"""Constants from docs/P2P_PROTOCOL.md section 2 and 11. Keep in sync with the Kotlin P2pConstants."""

SERVICE_UUID = "7c0a1e5f-6a3b-4e2d-9f11-c0471c710001"
INBOX_UUID = "7c0a1e5f-6a3b-4e2d-9f11-c0471c710002"   # write (with response); central -> peripheral
OUTBOX_UUID = "7c0a1e5f-6a3b-4e2d-9f11-c0471c710003"  # notify; peripheral -> central
CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

PROTOCOL_VERSION = 1          # highest version this implementation speaks
PROTOCOL_MIN_VERSION = 1      # lowest version this implementation accepts

FRAME_VERSION = 0x01
FRAME_HEADER_LEN = 7
DEFAULT_ATT_MTU = 23
REQUESTED_ATT_MTU = 517
MAX_FRAME_LEN = 512           # ATT attribute value limit
MIN_FRAME_LEN = 20            # ATT_MTU 23 - 3

MAX_JSON_LEN = 16_384
MAX_BLOB_LEN = 61_440         # 60 KiB JPEG
MAX_BODY_LEN = 2 + MAX_JSON_LEN + MAX_BLOB_LEN
MAX_CONCURRENT_REASSEMBLIES = 4
REASSEMBLY_TIMEOUT_MS = 15_000
HELLO_TIMEOUT_MS = 10_000
KEYX_TIMEOUT_MS = 30_000
MATCH_LINK_TIMEOUT_MS = 600_000
IDLE_TIMEOUT_MS = 300_000
PHOTO_MIN_FRAME_LEN = 182     # photos only when ATT_MTU >= 185

MAX_FIRST_NAME_BYTES = 64
MAX_BIO_BYTES = 280
MAX_CHAT_TEXT_BYTES = 4000
MAX_READ_REFS = 64
FRIEND_TAG_LEN = 8
FRIEND_TAGS_MAX = 64
FRIEND_TAGS_REAL_MAX = 56
FRIEND_TAGS_BLOCK = 8

# Inbound rate limits (token buckets: capacity, refill per second).
RATE_FRAMES = (300, 150)
RATE_MESSAGES = (30, 5)
RATE_CHATS = (10, 1)

EPHEMERAL_ID_BYTES = 16
EPHEMERAL_ID_ROTATION_MS = 15 * 60 * 1000
