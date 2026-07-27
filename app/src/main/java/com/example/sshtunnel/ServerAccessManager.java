package com.example.sshtunnel;

import android.util.Base64;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ServerAccessManager {
    static final class Credentials {
        final String host;
        final int port;
        final String user;
        final String password;

        Credentials(String host, int port, String user, String password) {
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

        ManagedUser(String label, String login, String password, String expires,
                    long dailyMb, long monthlyMb, long speedMbps) {
            this.label = label;
            this.login = login;
            this.password = password;
            this.expires = expires;
            this.dailyMb = dailyMb;
            this.monthlyMb = monthlyMb;
            this.speedMbps = speedMbps;
        }

        boolean forever() {
            return expires == null || expires.isEmpty();
        }
    }

    static List<ManagedUser> list(SecureStore store) throws Exception {
        return decodeUsers(run(storeCredentials(store), "list", null));
    }

    static ManagedUser create(SecureStore store, String label, String requestedLogin,
                              int days, long dailyMb, long monthlyMb, long speedMbps)
            throws Exception {
        String login = normalizeLogin(requestedLogin);
        String generatedPassword = randomPassword();
        JSONObject request = new JSONObject()
                .put("label", label.trim())
                .put("login", login)
                .put("password", generatedPassword)
                .put("expires", days <= 0 ? "" : LocalDate.now().plusDays(days).toString())
                .put("daily_mb", dailyMb)
                .put("monthly_mb", monthlyMb)
                .put("speed_mbps", speedMbps);
        List<ManagedUser> users = decodeUsers(run(storeCredentials(store), "create", request));
        for (ManagedUser user : users) {
            if (user.login.equals(login)) return user;
        }
        throw new Exception("Сервер создал пользователя, но не вернул его настройки.");
    }

    static void extend(SecureStore store, String login, int days) throws Exception {
        JSONObject request = new JSONObject()
                .put("login", login)
                .put("days", days);
        run(storeCredentials(store), "extend", request);
    }

    static void revoke(SecureStore store, String login) throws Exception {
        run(storeCredentials(store), "revoke",
                new JSONObject().put("login", login));
    }

    static JSONArray exportUsers(SecureStore store) throws Exception {
        return new JSONArray(run(storeCredentials(store), "export", null));
    }

    static void importUsers(Credentials destination, JSONArray users) throws Exception {
        run(destination, "import", new JSONObject().put("users", users));
    }

    static Credentials storeCredentials(SecureStore store) throws Exception {
        String host = store.getPlain("host", "").trim();
        String user = store.getPlain("user", "root").trim();
        String password = store.getSecret();
        if (host.isEmpty() || user.isEmpty() || password.isEmpty()) {
            throw new Exception("Сначала сохрани данные администратора сервера.");
        }
        int port;
        try {
            port = Integer.parseInt(store.getPlain("port", "22"));
        } catch (Exception error) {
            throw new Exception("Неверный SSH-порт.");
        }
        return new Credentials(host, port, user, password);
    }

    private static String run(Credentials credentials, String action, JSONObject request)
            throws Exception {
        Session session = new JSch().getSession(
                credentials.user, credentials.host, credentials.port);
        session.setPassword(credentials.password);
        session.setSocketFactory(new LowLatencySocketFactory(null));
        session.setConfig("StrictHostKeyChecking", "no");
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
                    item.optLong("speed_mbps", 0)));
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

    private static String randomPassword() {
        final String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom random = new SecureRandom();
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 24; i++) value.append(chars.charAt(random.nextInt(chars.length())));
        return value.toString();
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
                "import base64,datetime,json,os,pwd,subprocess,sys",
                "path='/etc/pelmeni-vpn/users.json'",
                "action=sys.argv[1]",
                "req=json.loads(base64.b64decode(sys.argv[2]).decode()) if len(sys.argv)>2 and sys.argv[2] else {}",
                "try:",
                " users=json.load(open(path,encoding='utf-8'))",
                "except Exception: users=[]",
                "def save():",
                " tmp=path+'.tmp'; open(tmp,'w',encoding='utf-8').write(json.dumps(users,ensure_ascii=False)); os.chmod(tmp,0o600); os.replace(tmp,path)",
                "def shell(*args,input=None): subprocess.run(args,input=input,text=True,check=True,stdout=subprocess.DEVNULL)",
                "login=req.get('login','')",
                "try:",
                " if action=='create':",
                "  if any(x['login']==login for x in users): raise Exception('Такой логин уже существует.')",
                "  try: pwd.getpwnam(login); raise Exception('Такой Linux-пользователь уже существует.')",
                "  except KeyError: pass",
                "  shell('useradd','-m','-g','pelmeni-vpn','-s','/bin/bash',login)",
                "  shell('chpasswd',input=login+':'+req['password']+'\\n')",
                "  shell('chage','-E',req.get('expires') or '-1',login)",
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
                " elif action=='revoke':",
                "  users[:]=[x for x in users if x['login']!=login]",
                "  subprocess.run(['userdel','-r',login],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)",
                "  save()",
                " elif action=='import':",
                "  for incoming in req.get('users',[]):",
                "   name=incoming['login']",
                "   try: pwd.getpwnam(name)",
                "   except KeyError: shell('useradd','-m','-g','pelmeni-vpn','-s','/bin/bash',name)",
                "   shell('usermod','-g','pelmeni-vpn',name)",
                "   shell('chpasswd',input=name+':'+incoming['password']+'\\n')",
                "   shell('chage','-E',incoming.get('expires') or '-1',name)",
                "   users=[x for x in users if x.get('login')!=name]; users.append(incoming)",
                "  save()",
                " elif action not in ('list','export'): raise Exception('Неизвестная операция.')",
                " subprocess.run(['systemctl','daemon-reload'],stdout=subprocess.DEVNULL)",
                " subprocess.run(['systemctl','enable','--now','pelmeni-user-policy.service'],stdout=subprocess.DEVNULL)",
                " subprocess.run(['systemctl','restart','pelmeni-user-policy.service'],stdout=subprocess.DEVNULL)",
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
                "users_path='/etc/pelmeni-vpn/users.json'; state_path='/etc/pelmeni-vpn/usage.json'",
                "def load(path,default):",
                " try: return json.load(open(path,encoding='utf-8'))",
                " except Exception: return default",
                "def nft(*args): return subprocess.run(['nft',*args],capture_output=True,text=True)",
                "def rebuild(users,state):",
                " nft('delete','table','inet','pelmeni_users')",
                " nft('add','table','inet','pelmeni_users')",
                " nft('add','chain','inet','pelmeni_users','output','{ type filter hook output priority 10; policy accept; }')",
                " for u in users:",
                "  try: uid=str(pwd.getpwnam(u['login']).pw_uid)",
                "  except KeyError: continue",
                "  nft('add','rule','inet','pelmeni_users','output','meta','skuid',uid,'counter','comment','pelmeni:'+u['login'])",
                "  s=state.get(u['login'],{})",
                "  blocked=(u.get('daily_mb',0)>0 and s.get('day_bytes',0)>=u['daily_mb']*1048576) or (u.get('monthly_mb',0)>0 and s.get('month_bytes',0)>=u['monthly_mb']*1048576)",
                "  if blocked: nft('add','rule','inet','pelmeni_users','output','meta','skuid',uid,'drop')",
                "  elif u.get('speed_mbps',0)>0: nft('add','rule','inet','pelmeni_users','output','meta','skuid',uid,'limit','rate','over',str(u['speed_mbps']*125),'kbytes/second','burst','256','kbytes','drop')",
                "def counters():",
                " out={}; data=nft('-j','list','chain','inet','pelmeni_users','output')",
                " if data.returncode: return out",
                " try: rows=json.loads(data.stdout).get('nftables',[])",
                " except Exception: return out",
                " for row in rows:",
                "  rule=row.get('rule',{}); comment=rule.get('comment','')",
                "  if not comment.startswith('pelmeni:'): continue",
                "  for expr in rule.get('expr',[]):",
                "   if 'counter' in expr: out[comment[8:]]=expr['counter'].get('bytes',0)",
                " return out",
                "users=load(users_path,[]); state=load(state_path,{})",
                "today=str(datetime.date.today()); month=today[:7]",
                "for u in users:",
                " s=state.setdefault(u['login'],{'day':today,'month':month,'day_bytes':0,'month_bytes':0,'last':0})",
                " if s.get('day')!=today: s.update(day=today,day_bytes=0)",
                " if s.get('month')!=month: s.update(month=month,month_bytes=0)",
                "rebuild(users,state)",
                "while True:",
                " time.sleep(10); users=load(users_path,[]); now=counters(); changed=False",
                " today=str(datetime.date.today()); month=today[:7]",
                " for u in users:",
                "  name=u['login']; s=state.setdefault(name,{'day':today,'month':month,'day_bytes':0,'month_bytes':0,'last':0})",
                "  if s.get('day')!=today: s.update(day=today,day_bytes=0); changed=True",
                "  if s.get('month')!=month: s.update(month=month,month_bytes=0); changed=True",
                "  raw=now.get(name,0); delta=max(0,raw-s.get('last',0)); s['last']=raw; s['day_bytes']=s.get('day_bytes',0)+delta; s['month_bytes']=s.get('month_bytes',0)+delta",
                "  was=s.get('blocked',False); block=(u.get('daily_mb',0)>0 and s['day_bytes']>=u['daily_mb']*1048576) or (u.get('monthly_mb',0)>0 and s['month_bytes']>=u['monthly_mb']*1048576)",
                "  s['blocked']=block; changed=changed or was!=block",
                " tmp=state_path+'.tmp'; open(tmp,'w').write(json.dumps(state)); os.replace(tmp,state_path)",
                " if changed:",
                "  rebuild(users,state)",
                "  for s in state.values(): s['last']=0",
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
