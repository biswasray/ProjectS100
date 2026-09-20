#!/system/bin/sh
# s100_media.sh - audio routing for the S100 shell's music and video
# players. Installed as /data/j2me/s100_media.sh.
#
# The players decode to PCM in the runtime (native/s100_media.c) and
# write it straight to the ALSA front end MultiMedia1 (/dev/snd/pcmC0D0p,
# group audio). The codec routing that KaiOS's audio HAL would do is
# reproduced here with tinymix from mixer_paths_qrd_skub.xml:
#
#   deep-buffer-playback   PRI_MI2S_RX Audio Mixer MultiMedia1 = 1
#   speaker                RX3 MIX1 INP1 = RX1, SPK DAC Switch = 1
#   headphones             MI2S_RX Channels = Two, RX1/RX2 MIX1 INP1 = RX1/RX2,
#                          RDAC2 MUX = RX2, HPHL/HPHR = Switch
#
#   s100_media.sh audio on [speaker|headphones|auto]   route (auto = jack state)
#   s100_media.sh audio off                            undo the routing
#   s100_media.sh audio volume <0-124>                 codec digital gain
#   s100_media.sh audio status                         jack=..., route=...

STATE_DIR=/data/j2me/tmp
JACK=/sys/class/switch/h2w/state

mkdir -p "$STATE_DIR" 2>/dev/null

mix() { tinymix "$@" >/dev/null 2>&1; }

jack() {
    j=$(cat "$JACK" 2>/dev/null)
    case "$j" in
        1|2) echo headphones ;;
        *) echo speaker ;;
    esac
}

route_off() {
    mix 'PRI_MI2S_RX Audio Mixer MultiMedia1' 0
    mix 'SPK DAC Switch' 0
    mix 'RX3 MIX1 INP1' ZERO
    mix 'RX1 MIX1 INP1' ZERO
    mix 'RX2 MIX1 INP1' ZERO
    mix 'HPHL' ZERO
    mix 'HPHR' ZERO
    rm -f "$STATE_DIR/audio.route"
}

route_on() {
    r=$1
    [ "$r" = "auto" ] || [ -z "$r" ] && r=$(jack)
    mix 'MI2S_RX Channels' Two
    case "$r" in
        headphones)
            mix 'RX1 MIX1 INP1' RX1
            mix 'RX2 MIX1 INP1' RX2
            mix 'RDAC2 MUX' RX2
            mix 'HPHL' Switch
            mix 'HPHR' Switch
            mix 'HPHL Volume' 9
            mix 'HPHR Volume' 9
            ;;
        *)
            r=speaker
            mix 'RX3 MIX1 INP1' RX1
            mix 'SPK DAC Switch' 1
            ;;
    esac
    mix 'PRI_MI2S_RX Audio Mixer MultiMedia1' 1
    echo "$r" > "$STATE_DIR/audio.route"
    echo "route=$r"
}

case "$1.$2" in
    audio.on)      route_on "$3" ;;
    audio.off)     route_off; echo "route=off" ;;
    audio.volume)
        v=${3:-84}
        mix 'RX1 Digital Volume' "$v"
        mix 'RX2 Digital Volume' "$v"
        mix 'RX3 Digital Volume' "$v"
        echo "volume=$v"
        ;;
    audio.status)
        echo "jack=$(jack)"
        echo "route=$(cat "$STATE_DIR/audio.route" 2>/dev/null || echo off)"
        [ -c /dev/snd/pcmC0D0p ] && echo "pcm=1" || echo "pcm=0"
        ;;
    *)
        echo "usage: s100_media.sh audio on [speaker|headphones|auto] | off | volume <n> | status" >&2
        exit 2
        ;;
esac
