package com.example.sshtunnel;

import android.util.Base64;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ServerAccessManager {
    static final class Credentials {
        final String host;
        final int port;
        final String user;
        final String password;
        final SecureStore store;
        final ServerProfiles.Profile profile;

        Credentials(String host, int port, String user, String password) {
            this(null, null, host, port, user, password);
        }

        private Credentials(
                SecureStore store, ServerProfiles.Profile profile,
                String host, int port, String user, String password) {
            this.store = store;
            this.profile = profile;
            this.host = host;
            this.port = port;
            this.user = user;
            this.password = password;
        }
    }

    static final class ManagedUser {
        final String label;
        final String login;
        final String password;
        final String expires;
        final long dailyMb;
        final long monthlyMb;
        final long speedMbps;
        final String accessCode;
        final long dayBytes;
        final long monthBytes;
        final boolean blocked;
        final boolean expired;
        final boolean policyHealthy;
        final String policyError;
        final long statusUpdatedAt;
        final long serverOffsetMinutes;
        final long issuedAt;
        final String publicPool;

        ManagedUser(String label, String login, String password, String expires,
                    long dailyMb, long monthlyMb, long speedMbps, String accessCode,
                    long dayBytes, long monthBytes, boolean blocked,
                    boolean expired, boolean policyHealthy,
                    String policyError, long statusUpdatedAt,
                    long serverOffsetMinutes, long issuedAt) {
            this(label, login, password, expires, dailyMb, monthlyMb, speedMbps,
                    accessCode, dayBytes, monthBytes, blocked, expired,
                    policyHealthy, policyError, statusUpdatedAt,
                    serverOffsetMinutes, issuedAt, "");
        }

        ManagedUser(String label, String login, String password, String expires,
                    long dailyMb, long monthlyMb, long speedMbps, String accessCode,
                    long dayBytes, long monthBytes, boolean blocked,
                    boolean expired, boolean policyHealthy,
                    String policyError, long statusUpdatedAt,
                    long serverOffsetMinutes, long issuedAt,
                    String publicPool) {
            this.label = label;
            this.login = login;
            this.password = password;
            this.expires = expires;
            this.dailyMb = dailyMb;
            this.monthlyMb = monthlyMb;
            this.speedMbps = speedMbps;
            this.accessCode = accessCode;
            this.dayBytes = dayBytes;
            this.monthBytes = monthBytes;
            this.blocked = blocked;
            this.expired = expired;
            this.policyHealthy = policyHealthy;
            this.policyError = policyError;
            this.statusUpdatedAt = statusUpdatedAt;
            this.serverOffsetMinutes = serverOffsetMinutes;
            this.issuedAt = issuedAt;
            this.publicPool = publicPool == null ? "" : publicPool;
        }

        boolean isPublic() {
            return !publicPool.isEmpty() || login.startsWith("pel_pub_");
        }

        boolean forever() {
            return expires == null || expires.isEmpty();
        }
    }

    static final class TlsBundle {
        final byte[] pkcs12;
        final String password;

        TlsBundle(byte[] pkcs12, String password) {
            this.pkcs12 = pkcs12;
            this.password = password;
        }
    }

    static List<ManagedUser> list(SecureStore store) throws Exception {
        return list(store, ServerProfiles.active(store));
    }

    static List<ManagedUser> list(
            SecureStore store, ServerProfiles.Profile profile) throws Exception {
        return decodeUsers(run(profileCredentials(store, profile), "list",
                new JSONObject().put("code_profile",
                        codeProfile(store, profile, false))));
    }

    static ManagedUser create(SecureStore store, String label, String requestedLogin,
                              int days, long dailyMb, long monthlyMb, long speedMbps)
            throws Exception {
        return create(store, ServerProfiles.active(store), label, requestedLogin,
                days, dailyMb, monthlyMb, speedMbps, false);
    }

    static ManagedUser create(
            SecureStore store, ServerProfiles.Profile profile,
            String label, String requestedLogin, int days,
            long dailyMb, long monthlyMb, long speedMbps,
            boolean useTls) throws Exception {
        String login = normalizeLogin(requestedLogin);
        JSONObject request = new JSONObject()
                .put("label", label.trim())
                .put("login", login)
                .put("days", days)
                .put("daily_mb", dailyMb)
                .put("monthly_mb", monthlyMb)
                .put("speed_mbps", speedMbps)
                .put("use_tls", useTls)
                .put("code_profile", codeProfile(store, profile, useTls));
        List<ManagedUser> users = decodeUsers(
                run(profileCredentials(store, profile), "create", request));
        for (ManagedUser user : users) {
            if (user.login.equals(login)) return user;
        }
        throw new Exception("Сервер создал пользователя, но не вернул его настройки.");
    }

    static TlsBundle fetchTlsBundle(
            SecureStore store, ServerProfiles.Profile profile) throws Exception {
        if (profile == null) throw new Exception("Сервер не выбран.");
        int port = Integer.parseInt(profile.sshPort);
        String password = ServerProfiles.password(store, profile.id);
        if (password.isEmpty()) throw new Exception("Нет пароля пользователя сервера.");
        Session session = SshHostKeys.newPinnedSession(
                store, profile, profile.user, profile.host, port);
        session.setPassword(password);
        session.setSocketFactory(new LowLatencySocketFactory(null));
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.connect(20_000);
        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        try {
            channel.connect(10_000);
            ByteArrayOutputStream bundle = new ByteArrayOutputStream();
            ByteArrayOutputStream bundlePassword = new ByteArrayOutputStream();
            channel.get(".pelmeni-tls.p12", bundle);
            channel.get(".pelmeni-tls-password", bundlePassword);
            String value = bundlePassword.toString(StandardCharsets.UTF_8.name()).trim();
            if (bundle.size() == 0 || value.isEmpty()) {
                throw new Exception("Сервер не выдал TLS-сертификат.");
            }
            return new TlsBundle(bundle.toByteArray(), value);
        } finally {
            if (channel.isConnected()) channel.disconnect();
            session.disconnect();
        }
    }

    static void extend(SecureStore store, String login, int days) throws Exception {
        extend(store, ServerProfiles.active(store), login, days);
    }

    static void extend(SecureStore store, ServerProfiles.Profile profile,
                       String login, int days) throws Exception {
        JSONObject request = new JSONObject()
                .put("login", login)
                .put("days", days);
        run(profileCredentials(store, profile), "extend", request);
    }

    static void revoke(SecureStore store, String login) throws Exception {
        revoke(store, ServerProfiles.active(store), login);
    }

    static void revoke(SecureStore store, ServerProfiles.Profile profile,
                       String login) throws Exception {
        run(profileCredentials(store, profile), "revoke",
                new JSONObject().put("login", login));
    }

    static ManagedUser updateLimits(
            SecureStore store, ServerProfiles.Profile profile, String login,
            long dailyMb, long monthlyMb, long speedMbps) throws Exception {
        JSONObject request = new JSONObject()
                .put("login", login)
                .put("daily_mb", dailyMb)
                .put("monthly_mb", monthlyMb)
                .put("speed_mbps", speedMbps);
        List<ManagedUser> users = decodeUsers(
                run(profileCredentials(store, profile), "limits", request));
        for (ManagedUser user : users) {
            if (user.login.equals(login)) return user;
        }
        throw new Exception("Сервер не вернул обновлённого пользователя.");
    }

    static void resetUsage(
            SecureStore store, ServerProfiles.Profile profile, String login)
            throws Exception {
        run(profileCredentials(store, profile), "reset",
                new JSONObject().put("login", login));
    }

    static JSONArray exportUsers(SecureStore store) throws Exception {
        return new JSONArray(run(storeCredentials(store), "export", null));
    }

    static void importUsers(Credentials destination, JSONArray users,
                            String serverName, String socksPort,
                            int windowKiB, int packetKiB, int mtu) throws Exception {
        JSONObject profile = new JSONObject()
                .put("name", serverName)
                .put("host", destination.host)
                .put("ssh_port", destination.port)
                .put("socks_port", socksPort)
                .put("window_kib", windowKiB)
                .put("packet_kib", packetKiB)
                .put("mtu", mtu);
        run(destination, "import", new JSONObject()
                .put("users", users)
                .put("code_profile", profile));
    }

    static Credentials storeCredentials(SecureStore store) throws Exception {
        return profileCredentials(store, ServerProfiles.active(store));
    }

    static Credentials profileCredentials(
            SecureStore store, ServerProfiles.Profile profile,
            String password) throws Exception {
        if (profile == null) throw new Exception("Сервер не выбран.");
        if (password == null || password.isEmpty()) {
            throw new Exception("Нет пароля администратора сервера.");
        }
        try {
            return new Credentials(store, profile, profile.host,
                    Integer.parseInt(profile.sshPort), profile.user, password);
        } catch (NumberFormatException error) {
            throw new Exception("Неверный SSH-порт.");
        }
    }

    private static Credentials profileCredentials(
            SecureStore store, ServerProfiles.Profile profile) throws Exception {
        if (profile == null) throw new Exception("Сервер не выбран.");
        String password = ServerProfiles.password(store, profile.id);
        return profileCredentials(store, profile, password);
    }

    private static String run(Credentials credentials, String action, JSONObject request)
            throws Exception {
        if (credentials.store == null || credentials.profile == null) {
            throw new Exception(
                    "Сначала сохрани сервер как профиль и подтверди его SSH host key.");
        }
        Session session = SshHostKeys.newPinnedSession(
                credentials.store, credentials.profile,
                credentials.user, credentials.host, credentials.port);
        session.setPassword(credentials.password);
        session.setSocketFactory(new LowLatencySocketFactory(null));
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive");
        session.connect(20_000);
        try {
            boolean root = execute(session, "id -u", "", 15_000).output.trim().equals("0");
            if (!root) {
                ExecResult sudo = execute(session, "sudo -S -p '' -v",
                        credentials.password + "\n", 30_000);
                if (sudo.exitStatus != 0) {
                    throw new Exception("Для управления людьми нужны права root или sudo.");
                }
            }
            String payload = request == null ? "" : Base64.encodeToString(
                    request.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            String script = managementScript(action, payload);
            ExecResult result = execute(session, root ? "bash -s" : "sudo -n bash -s",
                    script, 180_000);
            String marker = marker(result.output, "PELMENI_USERS=");
            if (result.exitStatus != 0 || marker.isEmpty()) {
                String message = marker(result.output, "PELMENI_ERROR=");
                throw new Exception(message.isEmpty()
                        ? "Сервер не применил настройки пользователей." : message);
            }
            return new String(Base64.decode(marker, Base64.DEFAULT), StandardCharsets.UTF_8);
        } finally {
            session.disconnect();
        }
    }

    private static List<ManagedUser> decodeUsers(String value) throws Exception {
        JSONArray array = new JSONArray(value);
        List<ManagedUser> users = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            users.add(new ManagedUser(
                    item.optString("label", item.getString("login")),
                    item.getString("login"),
                    item.optString("password", ""),
                    item.optString("expires", ""),
                    item.optLong("daily_mb", 0),
                    item.optLong("monthly_mb", 0),
                    item.optLong("speed_mbps", 0),
                    item.optString("access_code", ""),
                    item.optLong("day_bytes", 0),
                    item.optLong("month_bytes", 0),
                    item.optBoolean("blocked", false),
                    item.optBoolean("expired", false),
                    item.optBoolean("policy_healthy", false),
                    item.optString("policy_error", ""),
                    item.optLong("status_updated_at", 0),
                    item.optLong("server_offset_minutes", 0),
                    item.optLong("issued_at", 0),
                    item.optString("public_pool", "")));
        }
        return users;
    }

    private static String normalizeLogin(String value) throws Exception {
        String login = value.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9_-]", "_")
                .replaceAll("_+", "_");
        if (login.startsWith("-")) login = "_" + login.substring(1);
        if (login.isEmpty()) throw new Exception("Укажи логин латиницей.");
        if (!login.startsWith("pel_")) login = "pel_" + login;
        if (login.length() > 28) login = login.substring(0, 28);
        return login;
    }

    private static JSONObject codeProfile(
            SecureStore store, ServerProfiles.Profile profile,
            boolean useTls) throws Exception {
        if (profile == null) throw new Exception("Нет активного сервера.");
        JSONObject result = new JSONObject()
                .put("name", profile.name)
                .put("host", profile.host)
                .put("ssh_port", Integer.parseInt(profile.sshPort))
                .put("socks_port", profile.socksPort)
                .put("window_kib", profile.windowKiB)
                .put("packet_kib", profile.packetKiB)
                .put("mtu", profile.mtu);
        if (useTls) {
            if (!TlsTransport.isConfiguredForProfile(store, profile)) {
                throw new Exception("На выбранном сервере TLS не настроен.");
            }
            result.put("tls_enabled", true)
                    .put("tls_port", TlsTransport.portForProfile(store, profile))
                    .put("tls_ports", TlsTransport.portsForProfile(store, profile));
        } else {
            result.put("tls_enabled", false);
        }
        return result;
    }

    private static String managementScript(String action, String payload) {
        String worker = workerPython();
        String policy = policyPython();
        return "set -Eeuo pipefail\n"
                + "export DEBIAN_FRONTEND=noninteractive\n"
                + "if ! command -v python3 >/dev/null || ! command -v nft >/dev/null; then\n"
                + "  if ! command -v apt-get >/dev/null; then echo 'PELMENI_ERROR=Поддерживаются Debian и Ubuntu.'; exit 40; fi\n"
                + "  apt-get update -qq && apt-get install -y -qq python3 nftables >/dev/null\n"
                + "fi\n"
                + "install -d -m 0700 /etc/pelmeni-vpn\n"
                + "[ -f /etc/pelmeni-vpn/users.json ] || printf '[]' > /etc/pelmeni-vpn/users.json\n"
                + "groupadd -f pelmeni-vpn\n"
                + "install -d -m 0755 /etc/ssh/sshd_config.d\n"
                + "cat > /etc/ssh/sshd_config.d/90-pelmeni-users.conf <<'PELSSH'\n"
                + "Match Group pelmeni-vpn\n"
                + "    AllowTcpForwarding yes\n"
                + "    X11Forwarding no\n"
                + "    AllowAgentForwarding no\n"
                + "    PermitTunnel no\n"
                + "    ForceCommand internal-sftp\n"
                + "Match all\n"
                + "PELSSH\n"
                + "sshd -t\n"
                + "(systemctl reload ssh || systemctl reload sshd) >/dev/null 2>&1 || true\n"
                + "printf '%s' '" + Base64.encodeToString(policy.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP) + "' | base64 -d > /usr/local/sbin/pelmeni-user-policy\n"
                + "chmod 0700 /usr/local/sbin/pelmeni-user-policy\n"
                + "cat > /etc/systemd/system/pelmeni-user-policy.service <<'PELUNIT'\n"
                + "[Unit]\nDescription=Pelmeni VPN user limits\nAfter=network.target\n"
                + "[Service]\nType=simple\nExecStart=/usr/local/sbin/pelmeni-user-policy\n"
                + "Restart=always\nRestartSec=3\nNice=10\nCPUWeight=10\n"
                + "[Install]\nWantedBy=multi-user.target\nPELUNIT\n"
                + "printf '%s' '" + Base64.encodeToString(worker.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP) + "' | base64 -d > /tmp/pelmeni-users.py\n"
                + "python3 /tmp/pelmeni-users.py '" + action + "' '" + payload + "'\n";
    }

    private static String workerPython() {
        return String.join("\n",
                "import base64,datetime,json,os,pwd,secrets,shutil,string,subprocess,sys,time",
                "path='/etc/pelmeni-vpn/users.json'; usage_path='/etc/pelmeni-vpn/usage.json'",
                "action=sys.argv[1]",
                "req=json.loads(base64.b64decode(sys.argv[2]).decode()) if len(sys.argv)>2 and sys.argv[2] else {}",
                "try:",
                " users=json.load(open(path,encoding='utf-8'))",
                "except Exception: users=[]",
                "def save_json(target,value):",
                " tmp=target+'.tmp'; open(tmp,'w',encoding='utf-8').write(json.dumps(value,ensure_ascii=False)); os.chmod(tmp,0o600); os.replace(tmp,target)",
                "def save(): save_json(path,users)",
                "def shell(*args,input=None): subprocess.run(args,input=input,text=True,check=True,stdout=subprocess.DEVNULL)",
                "login=req.get('login','')",
                "profile=req.pop('code_profile',{})",
                "def server_offset_minutes():",
                " offset=datetime.datetime.now().astimezone().utcoffset() or datetime.timedelta()",
                " return int(offset.total_seconds()//60)",
                "def write_policy(user):",
                " try: account=pwd.getpwnam(user['login'])",
                " except KeyError: return",
                " data={'format':1,'expires':user.get('expires',''),'daily_mb':int(user.get('daily_mb',0)),'monthly_mb':int(user.get('monthly_mb',0)),'speed_mbps':int(user.get('speed_mbps',0)),'server_offset_minutes':server_offset_minutes(),'usage_reset_at':int(user.get('usage_reset_at',0)),'issued_at':int(user.get('issued_at',int(time.time())))}",
                " target=os.path.join(account.pw_dir,'.pelmeni-policy.json')",
                " tmp=target+'.tmp-'+secrets.token_hex(8)",
                " fd=os.open(tmp,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)",
                " try:",
                "  os.write(fd,json.dumps(data,ensure_ascii=False,separators=(',',':')).encode()); os.fchown(fd,account.pw_uid,account.pw_gid)",
                " finally: os.close(fd)",
                " os.replace(tmp,target)",
                "def make_code(user):",
                " data={'format':1,'name':profile.get('name',profile.get('host',''))+' · '+user.get('label',user['login']),'host':profile['host'],'ssh_port':str(profile.get('ssh_port',22)),'username':user['login'],'password':user['password'],'socks_port':str(profile.get('socks_port','1080')),'window_kib':profile.get('window_kib',1024),'packet_kib':profile.get('packet_kib',32),'mtu':profile.get('mtu',8500),'expires':user.get('expires',''),'daily_mb':int(user.get('daily_mb',0)),'monthly_mb':int(user.get('monthly_mb',0)),'speed_mbps':int(user.get('speed_mbps',0)),'issued_at':int(user.get('issued_at',int(time.time()))),'tls_enabled':bool(user.get('use_tls',False))}",
                " if data['tls_enabled']: data.update(tls_port=int(profile.get('tls_port',443)),tls_ports=str(profile.get('tls_ports',profile.get('tls_port',443))))",
                " raw=json.dumps(data,ensure_ascii=False,separators=(',',':')).encode()",
                " return 'PEL1-'+base64.urlsafe_b64encode(raw).decode().rstrip('=')",
                "def provision_tls(user):",
                " if not user.get('use_tls',False): return",
                " source='/etc/stunnel/pelmeni/client.p12'; secret='/etc/stunnel/pelmeni/p12.password'",
                " if not os.path.isfile(source) or not os.path.isfile(secret): raise Exception('TLS на сервере не настроен.')",
                " account=pwd.getpwnam(user['login']); home=account.pw_dir",
                " for src,name in ((source,'.pelmeni-tls.p12'),(secret,'.pelmeni-tls-password')):",
                "  target=os.path.join(home,name); shutil.copyfile(src,target); os.chown(target,account.pw_uid,account.pw_gid); os.chmod(target,0o600)",
                "now=int(time.time()); legacy_changed=False",
                "for existing in users:",
                " if int(existing.get('issued_at',0))<=0: existing['issued_at']=now; legacy_changed=True",
                "if legacy_changed: save()",
                "try:",
                " if action=='create':",
                "  if any(x['login']==login for x in users): raise Exception('Такой логин уже существует.')",
                "  try: pwd.getpwnam(login); raise Exception('Такой Linux-пользователь уже существует.')",
                "  except KeyError: pass",
                "  alphabet=string.ascii_letters+string.digits",
                "  req['password']=''.join(secrets.choice(alphabet) for _ in range(24))",
                "  req['issued_at']=int(time.time())",
                "  days=int(req.pop('days',0)); req['expires']=str(datetime.date.today()+datetime.timedelta(days=days)) if days>0 else ''",
                "  req['access_code']=make_code(req)",
                "  shell('useradd','-m','-g','pelmeni-vpn','-s','/bin/bash',login)",
                "  shell('chpasswd',input=login+':'+req['password']+'\\n')",
                "  shell('chage','-E',req.get('expires') or '-1',login)",
                "  provision_tls(req)",
                "  users.append(req); save()",
                " elif action=='extend':",
                "  found=False",
                "  for x in users:",
                "   if x['login']==login:",
                "    days=int(req.get('days',0))",
                "    if days<=0: x['expires']=''",
                "    else:",
                "     today=datetime.date.today(); old=x.get('expires','')",
                "     try: base=max(today,datetime.date.fromisoformat(old))",
                "     except Exception: base=today",
                "     x['expires']=str(base+datetime.timedelta(days=days))",
                "    found=True",
                "  if not found: raise Exception('Пользователь не найден.')",
                "  current=next(x for x in users if x['login']==login)",
                "  shell('chage','-E',current.get('expires') or '-1',login); save()",
                " elif action=='limits':",
                "  found=False",
                "  for x in users:",
                "   if x['login']==login:",
                "    x['daily_mb']=max(0,int(req.get('daily_mb',0)))",
                "    x['monthly_mb']=max(0,int(req.get('monthly_mb',0)))",
                "    x['speed_mbps']=max(0,int(req.get('speed_mbps',0)))",
                "    found=True",
                "  if not found: raise Exception('Пользователь не найден.')",
                "  save()",
                " elif action=='reset':",
                "  found=False",
                "  for x in users:",
                "   if x['login']==login: x['usage_reset_at']=max(int(time.time()),int(x.get('usage_reset_at',0))+1); found=True",
                "  if not found: raise Exception('Пользователь не найден.')",
                "  save()",
                "  subprocess.run(['systemctl','stop','pelmeni-user-policy.service'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)",
                "  try: usage=json.load(open(usage_path,encoding='utf-8'))",
                "  except Exception: usage={}",
                "  current=next(x for x in users if x['login']==login)",
                "  now=int(time.time()); origin=int(current.get('issued_at',now))",
                "  usage[login]={'day_period':max(0,(now-origin)//86400),'month_period':max(0,(now-origin)//2592000),'day_bytes':0,'month_bytes':0,'last':0,'blocked':False}",
                "  save_json(usage_path,usage)",
                " elif action=='revoke':",
                "  users[:]=[x for x in users if x['login']!=login]",
                "  subprocess.run(['userdel','-r',login],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)",
                "  save()",
                " elif action=='import':",
                "  for incoming in req.get('users',[]):",
                "   name=incoming['login']",
                "   if int(incoming.get('issued_at',0))<=0: incoming['issued_at']=int(time.time())",
                "   try: pwd.getpwnam(name)",
                "   except KeyError: shell('useradd','-m','-g','pelmeni-vpn','-s','/bin/bash',name)",
                "   shell('usermod','-g','pelmeni-vpn',name)",
                "   shell('chpasswd',input=name+':'+incoming['password']+'\\n')",
                "   shell('chage','-E',incoming.get('expires') or '-1',name)",
                "   provision_tls(incoming)",
                "   incoming['access_code']=make_code(incoming)",
                "   users=[x for x in users if x.get('login')!=name]; users.append(incoming)",
                "  save()",
                " elif action=='list' and profile:",
                "  changed=False",
                "  for x in users:",
                "   if not x.get('access_code'): x['access_code']=make_code(x); changed=True",
                "  if changed: save()",
                " elif action not in ('list','export'): raise Exception('Неизвестная операция.')",
                " for x in users: write_policy(x)",
                " shell('systemctl','daemon-reload')",
                " shell('systemctl','enable','--now','pelmeni-user-policy.service')",
                " try: os.remove('/etc/pelmeni-vpn/policy-status.json')",
                " except FileNotFoundError: pass",
                " started=int(time.time()); shell('systemctl','restart','pelmeni-user-policy.service')",
                " control={}",
                " for _ in range(10):",
                "  time.sleep(.5)",
                "  try: control=json.load(open('/etc/pelmeni-vpn/policy-status.json',encoding='utf-8'))",
                "  except Exception: control={}",
                "  if control.get('updated_at',0)>=started: break",
                " try: usage=json.load(open('/etc/pelmeni-vpn/usage.json',encoding='utf-8'))",
                " except Exception: usage={}",
                " healthy=bool(control.get('healthy',False))",
                " if action=='create' and any(int(req.get(k,0))>0 for k in ('daily_mb','monthly_mb','speed_mbps')) and not healthy:",
                "  subprocess.run(['userdel','-r',login],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)",
                "  users[:]=[x for x in users if x.get('login')!=login]; save()",
                "  raise Exception('Контроллер лимитов не запустился: '+control.get('error','нет диагностики'))",
                " for x in users:",
                "  stats=usage.get(x['login'],{})",
                "  x['day_bytes']=int(stats.get('day_bytes',0)); x['month_bytes']=int(stats.get('month_bytes',0)); x['blocked']=bool(stats.get('blocked',False))",
                "  x['expired']=bool(x.get('expires') and x['expires']<str(datetime.date.today()))",
                "  x['policy_healthy']=healthy; x['policy_error']=control.get('error',''); x['status_updated_at']=int(control.get('updated_at',0)); x['server_offset_minutes']=server_offset_minutes()",
                " data=json.dumps(users,ensure_ascii=False).encode()",
                " print('PELMENI_USERS='+base64.b64encode(data).decode())",
                "except Exception as e:",
                " print('PELMENI_ERROR='+str(e)); sys.exit(1)",
                "");
    }

    private static String policyPython() {
        return String.join("\n",
                "#!/usr/bin/python3",
                "import datetime,json,os,pwd,subprocess,time",
                "users_path='/etc/pelmeni-vpn/users.json'; state_path='/etc/pelmeni-vpn/usage.json'; status_path='/etc/pelmeni-vpn/policy-status.json'",
                "def load(path,default):",
                " try: return json.load(open(path,encoding='utf-8'))",
                " except Exception: return default",
                "def save_json(path,value):",
                " tmp=path+'.tmp'; open(tmp,'w',encoding='utf-8').write(json.dumps(value,ensure_ascii=False)); os.chmod(tmp,0o600); os.replace(tmp,path)",
                "def write_status(healthy,error=''): save_json(status_path,{'healthy':healthy,'error':error,'updated_at':int(time.time())})",
                "def ruleset(table,users,state):",
                " lines=['table inet '+table+' {',' chain output {','  type filter hook output priority 10; policy accept;']",
                " for u in users:",
                "  try: uid=str(pwd.getpwnam(u['login']).pw_uid)",
                "  except KeyError: continue",
                "  s=state.get(u['login'],{})",
                "  blocked=(u.get('daily_mb',0)>0 and s.get('day_bytes',0)>=u['daily_mb']*1048576) or (u.get('monthly_mb',0)>0 and s.get('month_bytes',0)>=u['monthly_mb']*1048576)",
                "  if blocked: lines.append('  meta skuid '+uid+' drop comment \"pelmeni-block:'+u['login']+'\"')",
                "  else:",
                "   rate=int(u.get('speed_mbps',0))*125",
                "   if rate>0: lines.append('  meta skuid '+uid+' limit rate over '+str(rate)+' kbytes/second burst '+str(max(16,min(256,rate//4)))+' kbytes drop comment \"pelmeni-speed:'+u['login']+'\"')",
                "   lines.append('  meta skuid '+uid+' counter comment \"pelmeni:'+u['login']+'\"')",
                " lines.extend([' }','}']); return '\\n'.join(lines)+'\\n'",
                "def run_nft(args,stdin=None): return subprocess.run(['nft',*args],input=stdin,capture_output=True,text=True)",
                "def rebuild(users,state):",
                " check=run_nft(['-c','-f','-'],ruleset('pelmeni_users_check',users,state))",
                " if check.returncode: raise RuntimeError('Проверка nftables: '+(check.stderr.strip() or check.stdout.strip()))",
                " run_nft(['delete','table','inet','pelmeni_users'])",
                " apply=run_nft(['-f','-'],ruleset('pelmeni_users',users,state))",
                " if apply.returncode: raise RuntimeError('Применение nftables: '+(apply.stderr.strip() or apply.stdout.strip()))",
                " verify=run_nft(['list','chain','inet','pelmeni_users','output'])",
                " if verify.returncode: raise RuntimeError('Правила nftables не появились: '+verify.stderr.strip())",
                " write_status(True,'')",
                "def counters():",
                " out={}; data=run_nft(['-j','list','chain','inet','pelmeni_users','output'])",
                " if data.returncode: return out",
                " try: rows=json.loads(data.stdout).get('nftables',[])",
                " except Exception: return out",
                " for row in rows:",
                "  rule=row.get('rule',{}); comment=rule.get('comment','')",
                "  if not comment.startswith('pelmeni:'): continue",
                "  for expr in rule.get('expr',[]):",
                "   if 'counter' in expr: out[comment[8:]]=expr['counter'].get('bytes',0)",
                " return out",
                "def main():",
                " users=load(users_path,[]); state=load(state_path,{})",
                " now_seconds=int(time.time())",
                " for u in users:",
                "  origin=int(u.get('issued_at',now_seconds)); day_period=max(0,(now_seconds-origin)//86400); month_period=max(0,(now_seconds-origin)//2592000)",
                "  s=state.setdefault(u['login'],{'day_period':day_period,'month_period':month_period,'day_bytes':0,'month_bytes':0,'last':0,'blocked':False})",
                "  if s.get('day_period')!=day_period: s.update(day_period=day_period,day_bytes=0)",
                "  if s.get('month_period')!=month_period: s.update(month_period=month_period,month_bytes=0)",
                " rebuild(users,state)",
                " for s in state.values(): s['last']=0",
                " save_json(state_path,state)",
                " while True:",
                "  time.sleep(2); users=load(users_path,[]); now=counters(); changed=False",
                "  now_seconds=int(time.time())",
                "  for u in users:",
                "   name=u['login']; origin=int(u.get('issued_at',now_seconds)); day_period=max(0,(now_seconds-origin)//86400); month_period=max(0,(now_seconds-origin)//2592000)",
                "   s=state.setdefault(name,{'day_period':day_period,'month_period':month_period,'day_bytes':0,'month_bytes':0,'last':0,'blocked':False})",
                "   if s.get('day_period')!=day_period: s.update(day_period=day_period,day_bytes=0); changed=True",
                "   if s.get('month_period')!=month_period: s.update(month_period=month_period,month_bytes=0); changed=True",
                "   raw=now.get(name,0); delta=max(0,raw-s.get('last',0)); s['last']=raw; s['day_bytes']=s.get('day_bytes',0)+delta; s['month_bytes']=s.get('month_bytes',0)+delta",
                "   was=bool(s.get('blocked',False)); block=(u.get('daily_mb',0)>0 and s['day_bytes']>=u['daily_mb']*1048576) or (u.get('monthly_mb',0)>0 and s['month_bytes']>=u['monthly_mb']*1048576)",
                "   s['blocked']=block; changed=changed or was!=block",
                "  save_json(state_path,state)",
                "  if changed:",
                "   rebuild(users,state)",
                "   for s in state.values(): s['last']=0",
                "   save_json(state_path,state)",
                "try: main()",
                "except Exception as error:",
                " write_status(False,str(error)); raise",
                "");
    }

    private static ExecResult execute(
            Session session, String command, String stdin, long timeoutMs) throws Exception {
        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        channel.setCommand(command);
        channel.setInputStream(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
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
                    throw new Exception("Сервер слишком долго применяет настройки.");
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
            if (line.startsWith(prefix)) return line.substring(prefix.length()).trim();
        }
        return "";
    }

    private static final class ExecResult {
        final int exitStatus;
        final String output;

        ExecResult(int exitStatus, String output) {
            this.exitStatus = exitStatus;
            this.output = output;
        }
    }

    private ServerAccessManager() {
    }
}
