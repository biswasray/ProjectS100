#!/system/bin/sh
# s100_cam.sh - camera backend for the S100 shell (Menu > Camera).
# Installed as /data/j2me/s100_cam.sh.
#
# The JioPhone's camera stack is Qualcomm's mm-camera: the sensor nodes
# (/dev/video1, /dev/video2) only talk to mm-qcamera-daemon, and the one
# client we can drive without KaiOS is the factory test tool
# /system/bin/mm-qcamera-app. The ODM build of it reads a single menu key
# from stdin:
#     2 / 3  back / front preview, every frame dumped to /data/back.yuv
#            (/data/front.yuv) as 640x480 planar YV12 (Y, then V, then U
#            plane) until the process is killed
#     4 / 5  back / front 1280x960 snapshot to /data/camera_snap.yuv, exits
# (see os/docs and the test_camera.sh / test_snap.sh scripts in /system/xbin).
#
#   s100_cam.sh preview <0|1>   run the preview in the foreground (the Java
#                               side spawns this and kills the process group)
#   s100_cam.sh stop            kill any camera app
#   s100_cam.sh snap <0|1>      take a snapshot; prints file=... size=...
#   s100_cam.sh audio <wav>     record the microphone into wav (foreground;
#                               stopped with "audio-stop")
#   s100_cam.sh audio-stop      stop the recording gracefully (SIGINT lets
#                               tinycap finish the WAV header)

BACK=/data/back.yuv
FRONT=/data/front.yuv
SNAP=/data/camera_snap.yuv
APP=/system/bin/mm-qcamera-app

kill_app() {
    pkill -9 mm-qcamera-app 2>/dev/null
}

case "$1" in
    preview)
        kill_app
        rm -f "$BACK" "$FRONT"
        if [ "$2" = "1" ]; then key=3; else key=2; fi
        cd /data || exit 1
        # keep stdin open: the app exits on EOF
        ( echo "$key"; while :; do sleep 3600; done ) | "$APP" > /dev/null 2>&1
        ;;
    stop)
        kill_app
        sleep 0.3
        echo "state=stopped"
        ;;
    snap)
        kill_app
        sleep 0.5
        rm -f "$SNAP"
        if [ "$2" = "1" ]; then key=5; else key=4; fi
        cd /data || exit 1
        ( echo "$key"; sleep 20 ) | timeout 25 "$APP" > /dev/null 2>&1
        kill_app
        if [ -f "$SNAP" ]; then
            echo "file=$SNAP"
            echo "size=$(stat -c %s "$SNAP" 2>/dev/null || wc -c < "$SNAP")"
        else
            echo "error=no picture taken"
        fi
        ;;
    audio)
        wav=$2
        [ -n "$wav" ] || exit 2
        # route the handset microphone to MultiMedia1 (mixer_paths_qrd_skub.xml:
        # "audio-record" + "handset-mic"); harmless if a control is missing
        tinymix 'MultiMedia1 Mixer TERT_MI2S_TX' 1 >/dev/null 2>&1
        tinymix 'MI2S_TX Channels' One >/dev/null 2>&1
        tinymix 'DEC1 MUX' ADC1 >/dev/null 2>&1
        tinymix 'ADC1 Volume' 6 >/dev/null 2>&1
        rm -f "$wav"
        exec tinycap "$wav" -D 0 -d 0 -c 1 -r 48000 -b 16
        ;;
    audio-stop)
        pid=$(pidof tinycap 2>/dev/null)
        if [ -n "$pid" ]; then
            kill -INT $pid 2>/dev/null
            i=0
            while [ $i -lt 20 ] && kill -0 $pid 2>/dev/null; do
                sleep 0.1; i=$((i + 1))
            done
            kill -9 $pid 2>/dev/null
        fi
        tinymix 'MultiMedia1 Mixer TERT_MI2S_TX' 0 >/dev/null 2>&1
        echo "state=stopped"
        ;;
    *)
        echo "usage: s100_cam.sh preview <0|1> | stop | snap <0|1> | audio <wav> | audio-stop" >&2
        exit 2
        ;;
esac
