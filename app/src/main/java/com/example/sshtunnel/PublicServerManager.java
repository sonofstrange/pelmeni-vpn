package com.example.sshtunnel;

import android.util.Base64;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class PublicServerManager {
    static PublicServerRegistry.Entry publish(
            SecureStore store, ServerProfiles.Profile profile,
            String name, String location, int days, long dailyMb,
            long monthlyMb, long speedMbps, int maxUsers,
            boolean useTls) throws Exception {
        // Installs or refreshes the common per-user policy controller before
        // the public registrar starts creating accounts.
        ServerAccessManager.list(store, profile);
        SshHostKeys.ScannedKey trusted =
                SshHostKeys.trustedKey(store, profile);
        if (trusted == null) {
            throw new Exception("Сначала подтверди SSH-ключ сервера.");
        }
        JSONObject request = new JSONObject()
                .put("name", name)
                .put("location", location)
                .put("host", profile.host)
                .put("ssh_port", Integer.parseInt(profile.sshPort))
                .put("socks_port", profile.socksPort)
                .put("window_kib", profile.windowKiB)
                .put("packet_kib", profile.packetKiB)
                .put("mtu", profile.mtu)
                .put("days", days)
                .put("daily_mb", dailyMb)
                .put("monthly_mb", monthlyMb)
                .put("speed_mbps", speedMbps)
                .put("max_users", maxUsers)
                .put("tls", useTls)
                .put("host_key_type", trusted.type)
                .put("host_key", trusted.encodedKey)
                .put("fingerprint", trusted.fingerprint);
        if (useTls) {
            request.put("tls_port",
                    TlsTransport.portForProfile(store, profile));
            request.put("tls_ports",
                    TlsTransport.portsForProfile(store, profile));
        }
        ServerAccessManager.Credentials credentials =
                ServerAccessManager.profileCredentials(
                        store, profile, ServerProfiles.password(store, profile.id));
        String encoded = Base64.encodeToString(
                request.toString().getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP);
        String output = runRoot(credentials, installerScript(encoded), 180_000);
        String marker = marker(output, "PELMENI_PUBLIC=");
        if (marker.isEmpty()) {
            String error = marker(output, "PELMENI_ERROR=");
            throw new Exception(error.isEmpty()
                    ? "Сервер не создал публичный доступ." : error);
        }
        JSONObject result = new JSONObject(new String(
                Base64.decode(marker, Base64.DEFAULT),
                StandardCharsets.UTF_8));
        PublicServerRegistry.Entry entry =
                new PublicServerRegistry.Entry(result, "");
        store.putEncrypted(storageKey(profile.id),
                result.toString().getBytes(StandardCharsets.UTF_8));
        return entry;
    }

    static void disable(
            SecureStore store, ServerProfiles.Profile profile,
            PublicServerRegistry.Entry entry) throws Exception {
        ServerAccessManager.Credentials credentials =
                ServerAccessManager.profileCredentials(
                        store, profile,
                        ServerProfiles.password(store, profile.id));
        String pool = entry.poolId.replaceAll("[^a-fA-F0-9]", "");
        if (pool.length() != entry.poolId.length() || pool.isEmpty()) {
            throw new Exception("Повреждены настройки публичного режима.");
        }
        String script = "set -Eeuo pipefail\n"
                + "userdel -r '" + entry.registrarUser + "' "
                + ">/dev/null 2>&1 || true\n"
                + "rm -f '/etc/sudoers.d/pelmeni-public-" + pool + "'\n"
                + "rm -f '/etc/ssh/sshd_config.d/91-pelmeni-public-"
                + pool + ".conf'\n"
                + "rm -f '/etc/pelmeni-vpn/public-pools/" + pool
                + ".json'\n"
                + "sshd -t\n"
                + "(systemctl reload ssh || systemctl reload sshd) "
                + ">/dev/null 2>&1\n"
                + "echo PELMENI_PUBLIC_DISABLED=1\n";
        String output = runRoot(credentials, script, 60_000);
        if (!output.contains("PELMENI_PUBLIC_DISABLED=1")) {
            throw new Exception("Сервер не подтвердил отключение.");
        }
        store.removeEncrypted(storageKey(profile.id));
    }

    static PublicServerRegistry.Entry saved(
            SecureStore store, String profileId) {
        byte[] value = store.getEncrypted(storageKey(profileId));
        if (value == null) return null;
        try {
            return new PublicServerRegistry.Entry(new JSONObject(
                    new String(value, StandardCharsets.UTF_8)), "");
        } catch (Exception ignored) {
            return null;
        }
    }

    static String claim(
            SecureStore store, PublicServerRegistry.Entry entry)
            throws Exception {
        ServerProfiles.Profile temporary = ServerProfiles.create(
                entry.name, entry.host, Integer.toString(entry.sshPort),
                entry.registrarUser, "1080",
                NetworkTuning.DEFAULT_WINDOW_KIB,
                NetworkTuning.DEFAULT_PACKET_KIB,
                NetworkTuning.DEFAULT_MTU);
        SshHostKeys.ScannedKey advertised = new SshHostKeys.ScannedKey(
                entry.host, entry.sshPort, entry.hostKeyType, entry.hostKey);
        if (!advertised.fingerprint.equals(entry.fingerprint)) {
            throw new Exception("Запись сервера повреждена.");
        }
        SshHostKeys.ScannedKey scanned = SshHostKeys.scan(store, temporary);
        if (!scanned.fingerprint.equals(entry.fingerprint)) {
            throw new Exception("SSH-ключ сервера не совпал с каталогом.");
        }
        SshHostKeys.trust(store, temporary, advertised);
        Session session = SshHostKeys.newPinnedSession(
                store, temporary, entry.registrarUser,
                entry.host, entry.sshPort);
        session.setPassword(entry.registrarPassword);
        session.setSocketFactory(new LowLatencySocketFactory(null));
        session.setConfig("PreferredAuthentications",
                "password,keyboard-interactive");
        try {
            session.connect(20_000);
            ExecResult result = execute(session, "claim", "", 60_000);
            String code = marker(result.output, "PEL_PUBLIC_CODE=");
            if (result.exitStatus != 0 || code.isEmpty()) {
                String error = marker(result.output, "PEL_PUBLIC_ERROR=");
                throw new Exception(error.isEmpty()
                        ? "Сервер не выдал личный доступ." : error);
            }
            return code;
        } finally {
            session.disconnect();
            SshHostKeys.clearProfile(store, temporary.id);
        }
    }

    private static String runRoot(
            ServerAccessManager.Credentials credentials,
            String script, long timeoutMs) throws Exception {
        Session session = SshHostKeys.newPinnedSession(
                credentials.store, credentials.profile,
                credentials.user, credentials.host, credentials.port);
        session.setPassword(credentials.password);
        session.setSocketFactory(new LowLatencySocketFactory(null));
        session.setConfig("PreferredAuthentications",
                "password,keyboard-interactive");
        session.connect(20_000);
        try {
            boolean root = execute(
                    session, "id -u", "", 15_000).output.trim().equals("0");
            if (!root) {
                ExecResult sudo = execute(session, "sudo -S -p '' -v",
                        credentials.password + "\n", 30_000);
                if (sudo.exitStatus != 0) {
                    throw new Exception("Нужны права root или sudo.");
                }
            }
            ExecResult result = execute(session,
                    root ? "bash -s" : "sudo -n bash -s",
                    script, timeoutMs);
            if (result.exitStatus != 0
                    && marker(result.output, "PELMENI_ERROR=").isEmpty()) {
                throw new Exception("Не удалось настроить публичный режим.");
            }
            return result.output;
        } finally {
            session.disconnect();
        }
    }

    private static String installerScript(String payload) {
        String claim = claimPython();
        return "set -Eeuo pipefail\n"
                + "export DEBIAN_FRONTEND=noninteractive\n"
                + "if ! command -v python3 >/dev/null; then "
                + "apt-get update -qq && apt-get install -y -qq python3; fi\n"
                + "install -d -m 0700 /etc/pelmeni-vpn/public-pools\n"
                + "printf '%s' '" + Base64.encodeToString(
                claim.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP)
                + "' | base64 -d > /usr/local/sbin/pelmeni-public-claim\n"
                + "chmod 0700 /usr/local/sbin/pelmeni-public-claim\n"
                + "python3 - '" + payload + "' <<'PY'\n"
                + installerPython() + "\nPY\n";
    }

    private static String installerPython() {
        return String.join("\n",
                "import base64,json,os,pwd,secrets,string,subprocess,sys",
                "r=json.loads(base64.b64decode(sys.argv[1]).decode())",
                "if r.get('tls') and not os.path.isfile('/etc/stunnel/pelmeni/client.p12'):",
                " print('PELMENI_ERROR=TLS на сервере не настроен.'); sys.exit(2)",
                "pool=secrets.token_hex(12); login='pelreg_'+pool[:10]",
                "alphabet=string.ascii_letters+string.digits",
                "password=''.join(secrets.choice(alphabet) for _ in range(28))",
                "r.update(format=1,pool_id=pool,registrar_user=login,registrar_password=password)",
                "subprocess.run(['useradd','-m','-s','/bin/bash',login],check=True)",
                "subprocess.run(['chpasswd'],input=login+':'+password+'\\n',text=True,check=True)",
                "path='/etc/pelmeni-vpn/public-pools/'+pool+'.json'",
                "open(path,'w',encoding='utf-8').write(json.dumps(r,ensure_ascii=False)); os.chmod(path,0o600)",
                "sudoers='/etc/sudoers.d/pelmeni-public-'+pool",
                "open(sudoers,'w').write(login+' ALL=(root) NOPASSWD: /usr/local/sbin/pelmeni-public-claim '+pool+' *\\n')",
                "os.chmod(sudoers,0o440)",
                "conf='/etc/ssh/sshd_config.d/91-pelmeni-public-'+pool+'.conf'",
                "open(conf,'w').write('Match User '+login+'\\n    PasswordAuthentication yes\\n    AllowTcpForwarding no\\n    X11Forwarding no\\n    AllowAgentForwarding no\\n    PermitTTY no\\n    ForceCommand sudo -n /usr/local/sbin/pelmeni-public-claim '+pool+' \"$SSH_CONNECTION\"\\nMatch all\\n')",
                "subprocess.run(['sshd','-t'],check=True)",
                "subprocess.run(['systemctl','reload','ssh'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)",
                "subprocess.run(['systemctl','reload','sshd'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)",
                "data=base64.b64encode(json.dumps(r,ensure_ascii=False,separators=(',',':')).encode()).decode()",
                "print('PELMENI_PUBLIC='+data)");
    }

    private static String claimPython() {
        return String.join("\n",
                "#!/usr/bin/python3",
                "import base64,datetime,fcntl,json,os,pwd,secrets,shutil,string,subprocess,sys,time",
                "def fail(message): print('PEL_PUBLIC_ERROR='+message); sys.exit(1)",
                "if len(sys.argv)<2: fail('Неверный запрос.')",
                "pool=sys.argv[1]; path='/etc/pelmeni-vpn/public-pools/'+pool+'.json'",
                "lock=open('/etc/pelmeni-vpn/public-claim.lock','w'); fcntl.flock(lock,fcntl.LOCK_EX)",
                "try: cfg=json.load(open(path,encoding='utf-8'))",
                "except Exception: fail('Публичный сервер отключён.')",
                "client=(sys.argv[2].split()[0] if len(sys.argv)>2 and sys.argv[2].strip() else 'unknown')",
                "claims_path='/etc/pelmeni-vpn/public-claims.json'",
                "try: claims=json.load(open(claims_path,encoding='utf-8'))",
                "except Exception: claims={}",
                "now=int(time.time())",
                "if now-int(claims.get(pool+':'+client,0))<60: fail('Повтори выдачу через минуту.')",
                "users_path='/etc/pelmeni-vpn/users.json'",
                "try: users=json.load(open(users_path,encoding='utf-8'))",
                "except Exception: users=[]",
                "today=str(datetime.date.today())",
                "active=sum(1 for u in users if u.get('public_pool')==pool and (not u.get('expires') or u['expires']>=today))",
                "if active>=int(cfg.get('max_users',50)): fail('На сервере закончились свободные места.')",
                "suffix=secrets.token_hex(5); login='pel_pub_'+pool[:5]+'_'+suffix",
                "alphabet=string.ascii_letters+string.digits",
                "password=''.join(secrets.choice(alphabet) for _ in range(24))",
                "issued=int(time.time()); days=int(cfg.get('days',30))",
                "expires=str(datetime.date.today()+datetime.timedelta(days=days)) if days>0 else ''",
                "u={'label':'Public '+cfg.get('name','server'),'login':login,'password':password,'issued_at':issued,'expires':expires,'daily_mb':int(cfg.get('daily_mb',0)),'monthly_mb':int(cfg.get('monthly_mb',0)),'speed_mbps':int(cfg.get('speed_mbps',0)),'use_tls':bool(cfg.get('tls',False)),'public_pool':pool}",
                "subprocess.run(['useradd','-m','-g','pelmeni-vpn','-s','/bin/bash',login],check=True)",
                "subprocess.run(['chpasswd'],input=login+':'+password+'\\n',text=True,check=True)",
                "subprocess.run(['chage','-E',expires or '-1',login],check=True)",
                "account=pwd.getpwnam(login)",
                "policy={'format':1,'expires':expires,'daily_mb':u['daily_mb'],'monthly_mb':u['monthly_mb'],'speed_mbps':u['speed_mbps'],'server_offset_minutes':0,'usage_reset_at':0,'issued_at':issued}",
                "target=os.path.join(account.pw_dir,'.pelmeni-policy.json')",
                "open(target,'w').write(json.dumps(policy,separators=(',',':'))); os.chown(target,account.pw_uid,account.pw_gid); os.chmod(target,0o600)",
                "if u['use_tls']:",
                " for src,name in (('/etc/stunnel/pelmeni/client.p12','.pelmeni-tls.p12'),('/etc/stunnel/pelmeni/p12.password','.pelmeni-tls-password')):",
                "  dst=os.path.join(account.pw_dir,name); shutil.copyfile(src,dst); os.chown(dst,account.pw_uid,account.pw_gid); os.chmod(dst,0o600)",
                "data={'format':1,'name':cfg.get('name',cfg['host'])+' · free','host':cfg['host'],'ssh_port':str(cfg.get('ssh_port',22)),'username':login,'password':password,'socks_port':str(cfg.get('socks_port','1080')),'window_kib':cfg.get('window_kib',1024),'packet_kib':cfg.get('packet_kib',32),'mtu':cfg.get('mtu',8500),'expires':expires,'daily_mb':u['daily_mb'],'monthly_mb':u['monthly_mb'],'speed_mbps':u['speed_mbps'],'issued_at':issued,'tls_enabled':u['use_tls']}",
                "if u['use_tls']: data.update(tls_port=int(cfg.get('tls_port',443)),tls_ports=str(cfg.get('tls_ports',cfg.get('tls_port',443))))",
                "raw=json.dumps(data,ensure_ascii=False,separators=(',',':')).encode()",
                "u['access_code']='PEL1-'+base64.urlsafe_b64encode(raw).decode().rstrip('=')",
                "users.append(u); tmp=users_path+'.tmp'; open(tmp,'w',encoding='utf-8').write(json.dumps(users,ensure_ascii=False)); os.chmod(tmp,0o600); os.replace(tmp,users_path)",
                "claims[pool+':'+client]=now; open(claims_path,'w').write(json.dumps(claims)); os.chmod(claims_path,0o600)",
                "subprocess.run(['systemctl','restart','pelmeni-user-policy.service'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)",
                "print('PEL_PUBLIC_CODE='+u['access_code'])");
    }

    private static ExecResult execute(
            Session session, String command, String input, long timeoutMs)
            throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        channel.setCommand(command);
        channel.setInputStream(new ByteArrayInputStream(
                input.getBytes(StandardCharsets.UTF_8)));
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
                    bytes.write(buffer, 0, count);
                }
                if (channel.isClosed() && output.available() == 0) break;
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    throw new Exception("Сервер слишком долго отвечает.");
                }
                Thread.sleep(100);
            }
            if (errors.size() > 0) bytes.write(errors.toByteArray());
            return new ExecResult(channel.getExitStatus(),
                    bytes.toString(StandardCharsets.UTF_8.name()));
        } finally {
            channel.disconnect();
        }
    }

    private static String marker(String output, String prefix) {
        for (String line : output.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String storageKey(String profileId) {
        return "public_pool_" + profileId;
    }

    private static final class ExecResult {
        final int exitStatus;
        final String output;

        ExecResult(int exitStatus, String output) {
            this.exitStatus = exitStatus;
            this.output = output;
        }
    }

    private PublicServerManager() {
    }
}
