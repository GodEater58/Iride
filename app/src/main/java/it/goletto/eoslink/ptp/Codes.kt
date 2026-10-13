package it.goletto.eoslink.ptp

/** Tipi di pacchetto PTP/IP (CIPA DC-005). */
object PtpIp {
    const val PORT = 15740
    const val INIT_COMMAND_REQUEST = 1
    const val INIT_COMMAND_ACK = 2
    const val INIT_EVENT_REQUEST = 3
    const val INIT_EVENT_ACK = 4
    const val INIT_FAIL = 5
    const val CMD_REQUEST = 6
    const val CMD_RESPONSE = 7
    const val EVENT = 8
    const val START_DATA = 9
    const val DATA = 10
    const val CANCEL = 11
    const val END_DATA = 12
    const val PING = 13
    const val PONG = 14
}

/** Operazioni PTP standard e estensioni Canon EOS (codici da libgphoto2). */
object PtpOp {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_STORAGE_IDS = 0x1004
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val GET_THUMB = 0x100A

    const val CANON_REMOTE_RELEASE = 0x910F
    const val CANON_SET_PROP_EX = 0x9110
    const val CANON_SET_REMOTE_MODE = 0x9114
    const val CANON_SET_EVENT_MODE = 0x9115
    const val CANON_GET_EVENT = 0x9116
    const val CANON_BULB_START = 0x9125
    const val CANON_BULB_END = 0x9126
    const val CANON_RELEASE_ON = 0x9128
    const val CANON_RELEASE_OFF = 0x9129
    const val CANON_GET_VIEWFINDER = 0x9153
}

object PtpResp {
    const val OK = 0x2001
    const val DEVICE_BUSY = 0x2019
    const val SESSION_ALREADY_OPEN = 0x201E
    const val CANON_NOT_READY = 0xA102
}

object CanonProp {
    const val EVF_OUTPUT_DEVICE = 0xD1B0
    const val EVF_MODE = 0xD1B3
}

object CanonEvent {
    const val OBJECT_ADDED_EX = 0xC181
}
