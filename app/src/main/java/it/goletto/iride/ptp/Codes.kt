package it.goletto.iride.ptp

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
    const val INITIATE_CAPTURE = 0x100E
    const val GET_DEVICE_PROP_DESC = 0x1014
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016

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
    const val CANON_DO_AF = 0x9154
    const val CANON_AF_CANCEL = 0x9160
    // messa a fuoco manuale e ingrandimento del live view (libgphoto2: DriveLens, Zoom, ZoomPosition)
    const val CANON_DRIVE_LENS = 0x9155
    const val CANON_ZOOM = 0x9158
    const val CANON_ZOOM_POSITION = 0x9159
}

/** Estensioni Nikon (codici da libgphoto2, camlibs/ptp2/ptp.h). */
object NikonOp {
    const val GET_EVENT = 0x90C7
    const val AF_DRIVE = 0x90C1
    const val DEVICE_READY = 0x90C8
    const val CAPTURE_SDRAM = 0x90C0
    const val START_LIVE_VIEW = 0x9201
    const val END_LIVE_VIEW = 0x9202
    const val GET_LIVE_VIEW_IMG = 0x9203
    const val CAPTURE_REC_IN_MEDIA = 0x9207
    const val TERMINATE_CAPTURE = 0x920C
    const val MF_DRIVE = 0x9204
}

/** Estensioni Sony "SDIO" (codici da libgphoto2). */
object SonyOp {
    const val SDIO_CONNECT = 0x9201
    const val GET_EXT_DEVICE_INFO = 0x9202
    const val SET_EXT_DEVICE_PROP_VALUE = 0x9205
    const val CONTROL_DEVICE = 0x9207
    const val GET_ALL_EXT_DEVICE_PROP_INFO = 0x9209
}

object PtpEvt {
    const val OBJECT_ADDED = 0x4002
    const val DEVICE_PROP_CHANGED = 0x4006
    const val CAPTURE_COMPLETE = 0x400D
}

/** Proprietà standard PTP. */
object PtpProp {
    const val FNUMBER = 0x5007
    const val EXPOSURE_TIME = 0x500D
    const val EXPOSURE_PROGRAM = 0x500E
    const val EXPOSURE_INDEX = 0x500F
    const val EXPOSURE_BIAS = 0x5010
    const val WHITE_BALANCE = 0x5005
}

object PtpResp {
    const val OK = 0x2001
    const val DEVICE_BUSY = 0x2019
    const val SESSION_ALREADY_OPEN = 0x201E
    const val CANON_NOT_READY = 0xA102
}

object CanonProp {
    const val EVF_OUTPUT_DEVICE = 0xD1B0
    // 0xD1B1 = modo EVF (acceso/spento); 0xD1B3 è la nitidezza, da non toccare
    const val EVF_MODE = 0xD1B1
}

object CanonEvent {
    const val OBJECT_ADDED_EX = 0xC181
    const val PROP_VALUE_CHANGED = 0xC189
    const val AVAIL_LIST_CHANGED = 0xC18A
}
