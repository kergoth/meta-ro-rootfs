SUMMARY = "Volatile bind mount setup and configuration for read-only-rootfs"
DESCRIPTION = "${SUMMARY}"
LICENSE = "MIT"
LIC_FILES_CHKSUM = "file://../COPYING.MIT;md5=5750f3aa4ea2b00c2bf21b2b2a7b714d"

SRC_URI = "\
    file://mount-copybind \
    file://COPYING.MIT \
    file://volatile-binds.service.in \
"

inherit allarch systemd

volatiledir ?= "${localstatedir}/volatile"
VOLATILE_BIND_FILES ?= "${volatiledir}/etc/resolv.conf /etc/resolv.conf\n"
VOLATILE_BIND_DIRS ?= "\
    ${volatiledir}/lib /var/lib\n\
    ${volatiledir}/root-home ${ROOT_HOME}\n\
    ${volatiledir}/media /media\n\
"
VOLATILE_BINDS = "${VOLATILE_BIND_DIRS}\n${VOLATILE_BIND_FILES}"
VOLATILE_BINDS[type] = "list"
VOLATILE_BINDS[separator] = "\n"

def volatile_systemd_services(d):
    services = []
    for line in oe.data.typed_value("VOLATILE_BINDS", d):
        if not line:
            continue
        what, where = line.split(None, 1)
        services.append("%s.service" % what[1:].replace("/", "-"))
    return " ".join(services)

SYSTEMD_SERVICES = "${@volatile_systemd_services(d)}"

DEPENDS += "${@'systemd-systemctl-native' if 'systemd' in DISTRO_FEATURES.split() else ''}"
FILES_${PN} += "${systemd_unitdir}/system/*.service"

generate_service_file () {
    spec="$1"
    mountpoint="$2"
    isfile="$3"
    servicefile="${spec#/}"
    servicefile="${servicefile//\//-}.service"

    if [ -n "$isfile" ]; then
        rodir="${mountpoint%/*}"
    else
        rodir="$mountpoint"
    fi

    sed -e "s#@what@#$spec#g; s#@where@#$mountpoint#g" \
        -e "s#@rwdir@#${volatiledir}#g; s#@rodir@#$rodir#g" \
        ${WORKDIR}/volatile-binds.service.in >$servicefile
}

do_compile () {
    while read spec mountpoint; do
        if [ -z "$spec" ]; then
            continue
        fi

        generate_service_file "$spec" "$mountpoint"
    done <<END
${@VOLATILE_BIND_DIRS.replace("\\n", "\n")}
END

    while read spec mountpoint; do
        if [ -z "$spec" ]; then
            continue
        fi

        generate_service_file "$spec" "$mountpoint" "true"
    done <<END
${@VOLATILE_BIND_FILES.replace("\\n", "\n")}
END

    if [ -e var-volatile-lib.service ]; then
        # As the seed is stored under /var/lib, ensure that this service runs
        # after the volatile /var/lib is mounted.
        sed -i -e "/^Before=/s/\$/ systemd-random-seed.service/" \
               -e "/^WantedBy=/s/\$/ systemd-random-seed.service/" \
               var-volatile-lib.service
    fi
}
do_compile[dirs] = "${WORKDIR}"

do_install () {
    install -d ${D}${base_sbindir}
    install -m 0755 mount-copybind ${D}${base_sbindir}/

    install -d ${D}${systemd_unitdir}/system
    for service in ${SYSTEMD_SERVICES}; do
        install -m 0644 $service ${D}${systemd_unitdir}/system/
    done
}
do_install[dirs] = "${WORKDIR}"

pkg_postinst_${PN} () {
    OPTS=""

    if [ -n "$D" ]; then
        OPTS="--root=$D"
    fi

    if "${@'true' if 'systemd' in DISTRO_FEATURES.split() else 'false'}"; then
        if type systemctl >/dev/null 2>/dev/null; then
            for service in ${SYSTEMD_SERVICES}; do
                systemctl $OPTS enable $service

                if [ -z "$D" ]; then
                    systemctl restart $service
                fi
            done
        fi
    fi
}

pkg_prerm_${PN () {
    if "${@'true' if 'systemd' in DISTRO_FEATURES.split() else 'false'}"; then
        if type systemctl >/dev/null 2>/dev/null; then
            if [ -z "$D" ]; then
                for service in ${SYSTEMD_SERVICES}; do
                    systemctl stop $service
                done
            fi

            for service in ${SYSTEMD_SERVICES}; do
                systemctl disable $service
            done
        fi
    fi
}
