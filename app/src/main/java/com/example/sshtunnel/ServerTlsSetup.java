package com.example.sshtunnel;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class ServerTlsSetup {
    static final int[] FALLBACK_PORTS = {8443};

    static final class Result {
        final byte[] pkcs12;
        final String password;
        final int port;

        Result(byte[] pkcs12, String password, int port) {
            this.pkcs12 = pkcs12;
            this.password = password;
            this.port = port;
        }
    }

    static Result install(SecureStore store) throws Exception {
        String host = store.getPlain("host", "").trim();
        String user = store.getPlain("user", "root").trim();
        String password = store.getSecret();
        int sshPort = parsePort(store.getPlain("port", "22"), 22);
        if (host.isEmpty() || user.isEmpty() || password.isEmpty()) {
            throw new Exception("Сначала сохрани адрес, пользователя и пароль сервера.");
        }

        Session session = SshHostKeys.newPinnedSession(
                store, user, host, sshPort);
        session.setPassword(password);
        session.setSocketFactory(new LowLatencySocketFactory(null));
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.connect(15_000);
        try {
            boolean root = execute(session, "id -u", "", 15_000)
                    .output.trim().equals("0");
            if (!root) {
                ExecResult sudo = execute(
                        session, "sudo -S -p '' -v", password + "\n", 30_000);
                if (sudo.exitStatus != 0) {
                    throw new Exception("Пользователю нужны права sudo с этим же паролем.");
                }
            }

            String command = root ? "bash -s" : "sudo -n bash -s";
            ExecResult setup = execute(
                    session, command, installScript(host, sshPort), 240_000);
            if (!setup.output.contains("PELmeni_OK=1")) {
                android.util.Log.e("PelmeniTLS",
                        "Server installer exit=" + setup.exitStatus
                                + ", output=" + diagnosticTail(setup.output));
                throw new Exception(explainSetupError(setup.output));
            }
            String bundle = marker(setup.output, "PELmeni_P12=");
            String bundlePassword = marker(setup.output, "PELmeni_PASSWORD=");
            if (bundle.isEmpty() || bundlePassword.isEmpty()) {
                throw new Exception("Сервер настроен, но не вернул клиентский сертификат.");
            }
            return new Result(Base64.getDecoder().decode(bundle),
                    bundlePassword, TlsTransport.DEFAULT_PORT);
        } finally {
            session.disconnect();
        }
    }

    static void verify(SecureStore store) throws Exception {
        String host = store.getPlain("host", "").trim();
        String user = store.getPlain("user", "root").trim();
        String password = store.getSecret();
        int sshPort = parsePort(store.getPlain("port", "22"), 22);
        Session session = SshHostKeys.newPinnedSession(
                store, user, host, sshPort);
        session.setPassword(password);
        session.setSocketFactory(TlsTransport.socketFactory(store, null));
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        try {
            session.connect(20_000);
        } finally {
            session.disconnect();
        }
    }

    static void addFallbackPort(SecureStore store, int tlsPort) throws Exception {
        if (!isAllowedFallback(tlsPort)) throw new Exception("Недопустимый резервный порт.");
        Session session = connectPlain(store);
        try {
            String password = store.getSecret();
            boolean root = execute(session, "id -u", "", 15_000)
                    .output.trim().equals("0");
            if (!root) {
                ExecResult sudo = execute(
                        session, "sudo -S -p '' -v", password + "\n", 30_000);
                if (sudo.exitStatus != 0) {
                    throw new Exception("Пользователю нужны права sudo с этим же паролем.");
                }
            }
            String command = root ? "bash -s" : "sudo -n bash -s";
            int sshPort = parsePort(store.getPlain("port", "22"), 22);
            ExecResult result = execute(session, command,
                    fallbackScript(tlsPort, sshPort), 60_000);
            if (!result.output.contains("PELmeni_FALLBACK_OK=1")) {
                android.util.Log.e("PelmeniTLS",
                        "Fallback port " + tlsPort + " installer exit="
                                + result.exitStatus + ", output="
                                + diagnosticTail(result.output));
                // A mobile route can disappear just after the remote command has
                // completed. Keep the port in the failover list: an unavailable
                // listener is harmless and will be skipped during connection.
                if (result.exitStatus == -1 && result.output.trim().isEmpty()) return;
                if (result.output.contains("PELmeni_ERROR=PORT_BUSY")) {
                    throw new Exception("Порт " + tlsPort + " уже занят.");
                }
                throw new Exception("Не удалось запустить TLS на порту " + tlsPort + ".");
            }
        } finally {
            session.disconnect();
        }
    }

    static void remove(SecureStore store) throws Exception {
        Session session = connectPlain(store);
        try {
            String password = store.getSecret();
            boolean root = execute(session, "id -u", "", 15_000)
                    .output.trim().equals("0");
            if (!root) {
                ExecResult sudo = execute(
                        session, "sudo -S -p '' -v", password + "\n", 30_000);
                if (sudo.exitStatus != 0) {
                    throw new Exception("Пользователю нужны права sudo с этим же паролем.");
                }
            }
            String command = root ? "bash -s" : "sudo -n bash -s";
            ExecResult result = execute(session, command, removeScript(), 60_000);
            if (!result.output.contains("PELmeni_TLS_REMOVED=1")) {
                android.util.Log.e("PelmeniTLS",
                        "TLS removal exit=" + result.exitStatus
                                + ", output=" + diagnosticTail(result.output));
                throw new Exception("Не удалось полностью удалить TLS с сервера.");
            }
        } finally {
            session.disconnect();
        }
    }

    private static Session connectPlain(SecureStore store) throws Exception {
        String host = store.getPlain("host", "").trim();
        String user = store.getPlain("user", "root").trim();
        String password = store.getSecret();
        int sshPort = parsePort(store.getPlain("port", "22"), 22);
        Session session = SshHostKeys.newPinnedSession(
                store, user, host, sshPort);
        session.setPassword(password);
        session.setSocketFactory(new LowLatencySocketFactory(null));
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.connect(15_000);
        return session;
    }

    private static ExecResult execute(
            Session session, String command, String stdin, long timeoutMs) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        channel.setCommand(command);
        channel.setInputStream(new ByteArrayInputStream(
                stdin.getBytes(StandardCharsets.UTF_8)));
        channel.setErrStream(errors);
        InputStream output = channel.getInputStream();
        channel.connect(10_000);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs;
        try {
            while (true) {
                while (output.available() > 0) {
                    int count = output.read(buffer);
                    if (count < 0) break;
                    if (bytes.size() + count > 1_000_000) {
                        throw new Exception("Сервер вернул слишком большой журнал установки.");
                    }
                    bytes.write(buffer, 0, count);
                }
                if (channel.isClosed() && output.available() == 0) break;
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    throw new Exception("Настройка сервера не завершилась за 4 минуты.");
                }
                Thread.sleep(100);
            }
            if (errors.size() > 0) {
                bytes.write(errors.toByteArray());
            }
            return new ExecResult(channel.getExitStatus(),
                    bytes.toString(StandardCharsets.UTF_8.name()));
        } finally {
            channel.disconnect();
        }
    }

    private static String installScript(String host, int sshPort) {
        String subjectAltName = host.matches("[0-9a-fA-F:.]+")
                ? "IP:" + host : "DNS:" + host;
        return "set -Eeuo pipefail\n"
                + "export DEBIAN_FRONTEND=noninteractive\n"
                + "if [ ! -f /etc/debian_version ] || ! command -v apt-get >/dev/null; then\n"
                + "  echo 'PELmeni_ERROR=UNSUPPORTED_OS'; exit 40\n"
                + "fi\n"
                + "if [ ! -f /etc/stunnel/pelmeni.conf ] && command -v ss >/dev/null "
                + "&& ss -ltnH | awk '{print $4}' | grep -Eq '(^|\\\\]):443$|:443$'; then\n"
                + "  echo 'PELmeni_ERROR=PORT_443_BUSY'; exit 43\n"
                + "fi\n"
                + "apt-get update -qq\n"
                + "apt-get install -y -qq stunnel4 openssl >/dev/null\n"
                + "install -d -m 0700 /etc/stunnel/pelmeni\n"
                + "cd /etc/stunnel/pelmeni\n"
                + "if [ ! -s ca.key ] || [ ! -s client.key ] || [ ! -s server.key ]; then\n"
                + "  rm -f ca.* server.* client.* client.p12 p12.password\n"
                + "  openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes "
                + "-keyout ca.key -out ca.crt -subj '/CN=Pelmeni VPN private CA' >/dev/null 2>&1\n"
                + "  openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr "
                + "-subj '/CN=Pelmeni VPN server' >/dev/null 2>&1\n"
                + "  printf '%s\\n' 'basicConstraints=CA:FALSE' "
                + "'keyUsage=digitalSignature,keyEncipherment' "
                + "'extendedKeyUsage=serverAuth' 'subjectAltName=" + subjectAltName
                + "' > server.ext\n"
                + "  openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial "
                + "-out server.crt -days 3650 -sha256 -extfile server.ext >/dev/null 2>&1\n"
                + "  openssl req -newkey rsa:2048 -nodes -keyout client.key -out client.csr "
                + "-subj '/CN=Pelmeni VPN Android client' >/dev/null 2>&1\n"
                + "  printf '%s\\n' 'basicConstraints=CA:FALSE' 'keyUsage=digitalSignature' "
                + "'extendedKeyUsage=clientAuth' > client.ext\n"
                + "  openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial "
                + "-out client.crt -days 3650 -sha256 -extfile client.ext >/dev/null 2>&1\n"
                + "  openssl rand -hex 24 > p12.password\n"
                + "fi\n"
                + "openssl pkcs12 -export -out client.p12 -inkey client.key -in client.crt "
                + "-certfile ca.crt -passout file:p12.password >/dev/null 2>&1\n"
                + "cat > /etc/stunnel/pelmeni.conf <<'PEL_STUNNEL'\n"
                + "foreground = yes\n"
                + "debug = notice\n"
                + "[pelmeni]\n"
                + "accept = 0.0.0.0:443\n"
                + "connect = 127.0.0.1:" + sshPort + "\n"
                + "cert = /etc/stunnel/pelmeni/server.crt\n"
                + "key = /etc/stunnel/pelmeni/server.key\n"
                + "CAfile = /etc/stunnel/pelmeni/ca.crt\n"
                + "verify = 2\n"
                + "sslVersionMin = TLSv1.2\n"
                + "TIMEOUTclose = 0\n"
                + "socket = l:TCP_NODELAY=1\n"
                + "socket = r:TCP_NODELAY=1\n"
                + "PEL_STUNNEL\n"
                + "id -u pelmeni-stunnel >/dev/null 2>&1 || "
                + "useradd --system --home /nonexistent --shell /usr/sbin/nologin pelmeni-stunnel\n"
                + "chown -R pelmeni-stunnel:pelmeni-stunnel /etc/stunnel/pelmeni\n"
                + "chmod 0600 /etc/stunnel/pelmeni/*.key /etc/stunnel/pelmeni/client.p12 "
                + "/etc/stunnel/pelmeni/p12.password\n"
                + "cat > /etc/systemd/system/pelmeni-stunnel.service <<'PEL_SERVICE'\n"
                + "[Unit]\n"
                + "Description=Pelmeni VPN mutual TLS wrapper\n"
                + "After=network-online.target\n"
                + "Wants=network-online.target\n\n"
                + "[Service]\n"
                + "Type=simple\n"
                + "User=pelmeni-stunnel\n"
                + "ExecStart=/usr/bin/stunnel4 /etc/stunnel/pelmeni.conf\n"
                + "Restart=on-failure\n"
                + "RestartSec=2\n"
                + "AmbientCapabilities=CAP_NET_BIND_SERVICE\n"
                + "CapabilityBoundingSet=CAP_NET_BIND_SERVICE\n"
                + "NoNewPrivileges=true\n"
                + "PrivateTmp=true\n"
                + "ProtectHome=true\n\n"
                + "[Install]\n"
                + "WantedBy=multi-user.target\n"
                + "PEL_SERVICE\n"
                + "systemctl daemon-reload\n"
                + "systemctl enable --now pelmeni-stunnel.service >/dev/null\n"
                + "sleep 1\n"
                + "if ! systemctl is-active --quiet pelmeni-stunnel.service; then\n"
                + "  journalctl -u pelmeni-stunnel.service -n 20 --no-pager || true\n"
                + "  echo 'PELmeni_ERROR=SERVICE_FAILED'; exit 44\n"
                + "fi\n"
                + "if command -v ufw >/dev/null && ufw status | grep -q '^Status: active'; then\n"
                + "  ufw allow 443/tcp comment 'Pelmeni VPN TLS' >/dev/null || true\n"
                + "fi\n"
                + "printf 'PELmeni_P12='; base64 -w0 client.p12; printf '\\n'\n"
                + "printf 'PELmeni_PASSWORD='; cat p12.password; printf '\\n'\n"
                + "echo 'PELmeni_OK=1'\n";
    }

    private static String fallbackScript(int tlsPort, int sshPort) {
        return "set -Eeuo pipefail\n"
                + "if [ ! -f /etc/stunnel/pelmeni.conf ]; then "
                + "echo 'PELmeni_ERROR=NO_CONFIG'; exit 45; fi\n"
                + "if ! grep -q '^# PELMENI_PORT_" + tlsPort
                + "_BEGIN$' /etc/stunnel/pelmeni.conf "
                + "&& ss -ltnH | awk '{print $4}' | grep -Eq '(^|\\\\]):"
                + tlsPort + "$|:" + tlsPort + "$'; then\n"
                + "  echo 'PELmeni_ERROR=PORT_BUSY'; exit 46\n"
                + "fi\n"
                + "sed -i '/^# PELMENI_PORT_" + tlsPort
                + "_BEGIN$/,/^# PELMENI_PORT_" + tlsPort
                + "_END$/d' /etc/stunnel/pelmeni.conf\n"
                + "cat >> /etc/stunnel/pelmeni.conf <<'PEL_FALLBACK'\n"
                + "# PELMENI_PORT_" + tlsPort + "_BEGIN\n"
                + "[pelmeni-" + tlsPort + "]\n"
                + "accept = 0.0.0.0:" + tlsPort + "\n"
                + "connect = 127.0.0.1:" + sshPort + "\n"
                + "cert = /etc/stunnel/pelmeni/server.crt\n"
                + "key = /etc/stunnel/pelmeni/server.key\n"
                + "CAfile = /etc/stunnel/pelmeni/ca.crt\n"
                + "verify = 2\n"
                + "sslVersionMin = TLSv1.2\n"
                + "TIMEOUTclose = 0\n"
                + "socket = l:TCP_NODELAY=1\n"
                + "socket = r:TCP_NODELAY=1\n"
                + "# PELMENI_PORT_" + tlsPort + "_END\n"
                + "PEL_FALLBACK\n"
                + "systemctl restart pelmeni-stunnel.service\n"
                + "sleep 1\n"
                + "systemctl is-active --quiet pelmeni-stunnel.service "
                + "|| { echo 'PELmeni_ERROR=SERVICE_FAILED'; exit 47; }\n"
                + "if command -v ufw >/dev/null && ufw status | grep -q '^Status: active'; then\n"
                + "  ufw allow " + tlsPort
                + "/tcp comment 'Pelmeni VPN TLS fallback' >/dev/null || true\n"
                + "fi\n"
                + "echo 'PELmeni_FALLBACK_OK=1'\n";
    }

    private static String removeScript() {
        return "set -Eeuo pipefail\n"
                + "systemctl disable --now pelmeni-stunnel.service >/dev/null 2>&1 || true\n"
                + "rm -f /etc/systemd/system/pelmeni-stunnel.service\n"
                + "rm -f /etc/stunnel/pelmeni.conf\n"
                + "rm -rf /etc/stunnel/pelmeni\n"
                + "systemctl daemon-reload\n"
                + "systemctl reset-failed pelmeni-stunnel.service >/dev/null 2>&1 || true\n"
                + "if command -v ufw >/dev/null && ufw status | grep -q '^Status: active'; then\n"
                + "  ufw --force delete allow 443/tcp >/dev/null 2>&1 || true\n"
                + "  ufw --force delete allow 8443/tcp >/dev/null 2>&1 || true\n"
                + "fi\n"
                + "if id -u pelmeni-stunnel >/dev/null 2>&1; then\n"
                + "  userdel pelmeni-stunnel >/dev/null 2>&1 || true\n"
                + "fi\n"
                + "echo 'PELmeni_TLS_REMOVED=1'\n";
    }

    private static boolean isAllowedFallback(int port) {
        for (int allowed : FALLBACK_PORTS) {
            if (port == allowed) return true;
        }
        return false;
    }

    private static String marker(String output, String prefix) {
        for (String line : output.split("\\r?\\n")) {
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return "";
    }

    private static String explainSetupError(String output) {
        if (output.contains("PELmeni_ERROR=UNSUPPORTED_OS")) {
            return "Автонастройка поддерживает Debian и Ubuntu.";
        }
        if (output.contains("PELmeni_ERROR=PORT_443_BUSY")) {
            return "Порт 443 уже занят другим сервисом. Сервер не изменён.";
        }
        if (output.contains("PELmeni_ERROR=SERVICE_FAILED")) {
            return "stunnel установлен, но сервис не запустился. Проверь журнал systemd.";
        }
        if (output.toLowerCase(java.util.Locale.ROOT).contains("permission denied")) {
            return "Недостаточно прав root/sudo для настройки сервера.";
        }
        return "Не удалось автоматически настроить TLS на сервере.";
    }

    private static String diagnosticTail(String output) {
        String safe = output
                .replaceAll("(?m)^PELmeni_P12=.*$", "PELmeni_P12=[hidden]")
                .replaceAll("(?m)^PELmeni_PASSWORD=.*$", "PELmeni_PASSWORD=[hidden]");
        return safe.substring(Math.max(0, safe.length() - 3000));
    }

    private static int parsePort(String value, int fallback) {
        try {
            int port = Integer.parseInt(value);
            return port >= 1 && port <= 65535 ? port : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static final class ExecResult {
        final int exitStatus;
        final String output;

        ExecResult(int exitStatus, String output) {
            this.exitStatus = exitStatus;
            this.output = output;
        }
    }

    private ServerTlsSetup() {
    }
}
