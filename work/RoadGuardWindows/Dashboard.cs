using System;
using System.IO;
using System.Net;
using System.Drawing;
using System.Windows.Forms;
using System.Collections.Generic;
using System.Web.Script.Serialization;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Threading.Tasks;
using System.Diagnostics;

class Dashboard : Form {
    readonly JavaScriptSerializer json=new JavaScriptSerializer();
    readonly string dir;
    string admin,pin;
    Label state=new Label(),location=new Label(),backupState=new Label();
    DataGridView grid=new DataGridView();
    CheckBox auto=new CheckBox();
    CheckBox autoLive=new CheckBox();
    bool autoLiveAttempted,closing;
    volatile string liveStatus="";
    long livePacketAt;
    NumericUpDown budget=new NumericUpDown();
    bool refreshing;
    bool settingsLoaded;
    System.Collections.IEnumerable timelineCopies=new object[0];string timelinePin="";
    Process live;
    HttpWebRequest liveRequest;
    double? lat,lon;
    public Dashboard(string root) {
        dir=root; Text="RoadGuard • Windows Companion"; Width=1120;Height=760;MinimumSize=new Size(800,600);
        BackColor=Color.FromArgb(12,20,32);ForeColor=Color.White;Font=new Font("Segoe UI",11);
        var config=Parse(File.ReadAllText(Path.Combine(dir,"config.json")));
        admin=(string)config["adminToken"]; pin=Hash(File.ReadAllBytes(Path.Combine(dir,"server.cer")));
        ServicePointManager.SecurityProtocol=SecurityProtocolType.Tls12;
        ServicePointManager.ServerCertificateValidationCallback=(s,c,ch,e)=>c!=null && Hash(c.GetRawCertData())==pin;
        var main=new TableLayoutPanel {Dock=DockStyle.Fill,ColumnCount=1,RowCount=7,Padding=new Padding(18)};
        main.RowStyles.Add(new RowStyle(SizeType.Absolute,70));main.RowStyles.Add(new RowStyle(SizeType.Absolute,54));
        main.RowStyles.Add(new RowStyle(SizeType.Absolute,32));
        main.RowStyles.Add(new RowStyle(SizeType.Absolute,118));main.RowStyles.Add(new RowStyle(SizeType.Absolute,65));
        main.RowStyles.Add(new RowStyle(SizeType.Percent,100));main.RowStyles.Add(new RowStyle(SizeType.Absolute,48));
        state.Dock=DockStyle.Fill;state.Font=new Font(Font,FontStyle.Bold);state.Text="Connecting to local server…";main.Controls.Add(state);
        var actions=new FlowLayoutPanel {Dock=DockStyle.Fill};
        actions.Controls.Add(Button("Pair car",async()=>await PairCar()));
        actions.Controls.Add(Button("Pair phone",async()=>{var p=await Api("/admin/pair-code",new {});MessageBox.Show("PC addresses: "+p["addresses"]+"\nCode: "+p["code"]+"\nSecurity check: "+pin.Substring(0,12)+"\n\nPhone > Enter code: type PC address:8944 and this code.\nUse the private VPN address across different networks.\nValid for 2 minutes.","Connect phone");}));
        actions.Controls.Add(Button("Live video",async()=>{await Task.Yield();StartLive();}));
        actions.Controls.Add(Button("Timeline",async()=>{await Task.Yield();new Timeline(dir,timelineCopies,timelinePin).Show(this);}));
        actions.Controls.Add(Button("Refresh",async()=>{await Api("/admin/refresh",new {});await RefreshStatus();}));
        actions.Controls.Add(Button("Revoke phones",async()=>{if(MessageBox.Show("Disconnect all paired phones?","Confirm",MessageBoxButtons.YesNo)==DialogResult.Yes)await Api("/admin/revoke",new {});}));
        main.Controls.Add(actions);
        autoLive.Text="Open live automatically when connected (uses data)";autoLive.Dock=DockStyle.Fill;
        string autoLiveFile=Path.Combine(dir,"dashboard-auto-live.txt");
        autoLive.Checked=!File.Exists(autoLiveFile)||File.ReadAllText(autoLiveFile).Trim()!="off";
        autoLive.CheckedChanged+=(s,e)=>{try {File.WriteAllText(autoLiveFile,autoLive.Checked?"on":"off");autoLiveAttempted=false;}catch(Exception ex){MessageBox.Show(ex.Message,"Could not save auto-live setting");}};
        main.Controls.Add(autoLive);
        location.Dock=DockStyle.Fill;location.Text="Location sharing must be enabled on the car.";main.Controls.Add(location);
        var backup=new FlowLayoutPanel {Dock=DockStyle.Fill};
        auto.Text="Automatic PC backup";auto.Width=220;auto.ForeColor=Color.White;
        budget.Minimum=1;budget.Maximum=2000;budget.Value=10;budget.Width=70;
        backup.Controls.Add(auto);backup.Controls.Add(budget);backup.Controls.Add(new Label {Text="GB limit",Width=70});
        backup.Controls.Add(Button("Save backup settings",async()=>{await Api("/admin/backup-settings",new {enabled=auto.Checked,gb=(int)budget.Value});}));
        backup.Controls.Add(Button("Back up now",async()=>{await Api("/admin/backup-now",new {});}));
        backup.Controls.Add(Button("Live map",async()=>{var session=await Api("/admin/map-session",new {});var url=new Uri((string)session["url"]);if(url.Scheme!="http"||url.Host!="127.0.0.1")throw new Exception("Invalid local map address");Process.Start(new ProcessStartInfo(url.AbsoluteUri){UseShellExecute=true});}));
        main.Controls.Add(backup);
        grid.Dock=DockStyle.Fill;grid.ReadOnly=true;grid.AllowUserToAddRows=false;grid.AutoSizeColumnsMode=DataGridViewAutoSizeColumnsMode.Fill;grid.SelectionMode=DataGridViewSelectionMode.FullRowSelect;
        grid.BackgroundColor=Color.FromArgb(24,38,55);grid.DefaultCellStyle.ForeColor=Color.Black;
        grid.Columns.Add("date","Recording (car clock)");grid.Columns.Add("size","Size MB");grid.Columns.Add("status","State / PC copy");
        main.Controls.Add(grid);
        var menu=new ContextMenuStrip();
        menu.Items.Add("Save this recording to PC",null,async(s,e)=>{
            var row=grid.CurrentRow;
            if(row==null||!(row.Cells[0].Tag is string))return;
            try {await Api("/admin/save-clip",new {id=(string)row.Cells[0].Tag});backupState.Text="Saving the selected recording…";}
            catch(Exception ex){MessageBox.Show(ex.Message,"Save recording");}
        });
        grid.CellMouseDown+=(s,e)=>{if(e.Button==MouseButtons.Right&&e.RowIndex>=0)grid.CurrentCell=grid.Rows[e.RowIndex].Cells[0];};
        grid.ContextMenuStrip=menu;
        menu.Opening+=(s,e)=>{e.Cancel=grid.CurrentRow==null||!(grid.CurrentRow.Cells[0].Tag is string);};
        backupState.Dock=DockStyle.Fill;main.Controls.Add(backupState);
        Controls.Add(main);
        grid.CellDoubleClick+=(s,e)=> {if(e.RowIndex>=0 && grid.Rows[e.RowIndex].Tag is string) {
            string file=Path.GetFullPath(Path.Combine(dir,(string)grid.Rows[e.RowIndex].Tag));
            if(file.StartsWith(Path.GetFullPath(Path.Combine(dir,"Recordings"))+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)&&File.Exists(file))Process.Start(new ProcessStartInfo(file){UseShellExecute=true});
        }};
        var timer=new Timer {Interval=5000};timer.Tick+=async(s,e)=>await RefreshStatus();timer.Start();
        Shown+=async(s,e)=>await RefreshStatus();
        FormClosed+=(s,e)=>{closing=true;timer.Stop();if(liveRequest!=null)liveRequest.Abort();if(live!=null&&!live.HasExited)live.Kill();};
    }
    Button Button(string text,Func<Task> action) {
        var b=new Button {Text=text,AutoSize=true,Height=40,BackColor=Color.FromArgb(25,86,119),ForeColor=Color.White,FlatStyle=FlatStyle.Flat};
        b.Click+=async(s,e)=>{b.Enabled=false;try{await action();}catch(Exception ex){MessageBox.Show(ex.Message,"RoadGuard");}finally{if(!b.IsDisposed)b.Enabled=true;}};return b;
    }
    Dictionary<string,object> Parse(string text) {return json.Deserialize<Dictionary<string,object>>(text);}
    static string Hash(byte[] bytes) {using(var sha=SHA256.Create())return BitConverter.ToString(sha.ComputeHash(bytes)).Replace("-","").ToLowerInvariant();}
    HttpWebRequest Request(string route) {
        var request=(HttpWebRequest)WebRequest.Create("https://127.0.0.1:8944"+route);
        request.Proxy=null;request.AllowAutoRedirect=false;request.Headers["Authorization"]="Bearer "+admin;request.Timeout=15000;return request;
    }
    async Task<Dictionary<string,object>> Api(string route,object data=null) {
        var request=Request(route);
        if(data!=null) {request.Method="POST";request.ContentType="application/json";using(var writer=new StreamWriter(await request.GetRequestStreamAsync()))await writer.WriteAsync(json.Serialize(data));}
        using(var response=await request.GetResponseAsync())using(var reader=new StreamReader(response.GetResponseStream()))return Parse(await reader.ReadToEndAsync());
    }
    static string Prompt(string title,string initial="") {
        using(var f=new Form {Text=title,Width=520,Height=170,StartPosition=FormStartPosition.CenterParent}) {
            var text=new TextBox {Dock=DockStyle.Top,Text=initial};var ok=new Button {Dock=DockStyle.Bottom,Text="Continue",DialogResult=DialogResult.OK};f.Controls.Add(text);f.Controls.Add(ok);f.AcceptButton=ok;
            return f.ShowDialog()==DialogResult.OK?text.Text.Trim():null;
        }
    }
    async Task PairCar() {
        string host=Prompt("Car IPv4:8943 (use private VPN address for remote access)");if(host==null)return;
        var identity=await Api("/admin/probe",new {host});
        if(MessageBox.Show("Compare with the car's Connect phone screen:\n\n"+((string)identity["pin"]).Substring(0,12)+"\n\nMatches exactly?","Verify car identity",MessageBoxButtons.YesNo)!=DialogResult.Yes)return;
        string code=Prompt("8-digit code shown on car");if(code==null)return;
        await Api("/admin/pair-car",new {host,pin=identity["pin"],code});await RefreshStatus();
    }
    async Task RefreshStatus() {
        if(refreshing||IsDisposed)return;refreshing=true;
        try {
            var s=await Api("/admin/status");if(IsDisposed)return;
            bool online=(bool)s["online"];
            bool test=s.ContainsKey("testMode")&&(bool)s["testMode"];
            Text=test?"RoadGuard • TEST SIMULATION — not your car":"RoadGuard • Windows Companion";
            long packetAt=System.Threading.Interlocked.Read(ref livePacketAt);
            bool recentVideo=packetAt>0&&(Stopwatch.GetTimestamp()-packetAt)/(double)Stopwatch.Frequency<5;
            string videoState=recentVideo?liveStatus:live!=null&&!live.HasExited?"Video waiting / stalled — last image may be stale":liveStatus;
            state.Text=(test?"TEST SIMULATION — NO REAL CAR CONNECTED\n":"")+"RoadGuard "+s["app"]+"  •  "+(online?(test?"SIMULATED ONLINE":"● CAR ONLINE"):recentVideo?"STREAM CONNECTED • STATUS RETRYING":"CAR UNREACHABLE • RETRYING")+"\n"+s["error"]+"\n"+videoState;
            state.ForeColor=online&&!test?Color.LightGreen:Color.Orange;
            if(online&&!test&&autoLive.Checked&&!autoLiveAttempted) {
                autoLiveAttempted=true;
                try {StartLive();}catch(Exception ex){state.Text+="\nAuto live unavailable: "+ex.Message;}
            }
            backupState.Text="Backup: "+s["backupMessage"]+"   •   Right-click a finished recording to save it. Double-click a PC copy to play.";
            if(!settingsLoaded) {auto.Checked=(bool)s["backupEnabled"];budget.Value=Convert.ToDecimal(s["backupGB"]);settingsLoaded=true;}
            var loc=s["location"] as Dictionary<string,object>;
            lat=null;lon=null;
            if(loc!=null&&loc.ContainsKey("latitude")) {
                lat=Convert.ToDouble(loc["latitude"]);lon=Convert.ToDouble(loc["longitude"]);
                long age=Convert.ToInt64(loc["ageMs"])+(long)(DateTime.UtcNow-new DateTime(1970,1,1)).TotalMilliseconds-Convert.ToInt64(loc["receivedAt"]);
                bool fresh=online&&age<=30000&&loc.ContainsKey("fresh")&&(bool)loc["fresh"];
                location.Text=(test?"SIMULATED LOCATION":fresh?"CURRENT LOCATION":"LAST KNOWN • NOT LIVE")+"  ·  "+Math.Max(0,age/1000)+" seconds old\nLatitude: "+lat+"   Longitude: "+lon+"   Accuracy: ±"+loc["accuracyMeters"]+" m\n"+loc["place"]+"\n"+(fresh&&loc.ContainsKey("message")?loc["message"]:"Waiting for a confirmed fresh position")+"\nUpdates every 15 seconds; Refresh requests a fresh fix (may take time).";
            } else location.Text=loc!=null&&loc.ContainsKey("message")?(string)loc["message"]:"Location unavailable. Enable GPS sharing in the car settings.";
            if(grid.ContextMenuStrip.Visible)return;
            grid.Rows.Clear();
            var copies=(System.Collections.IEnumerable)s["backups"];
            timelineCopies=copies;var timelineCar=s["car"] as Dictionary<string,object>;timelinePin=timelineCar==null?"":(string)timelineCar["pin"];
            foreach(Dictionary<string,object> c in (System.Collections.IEnumerable)s["recordings"]) {
                Dictionary<string,object> saved=null;
                var car=s["car"] as Dictionary<string,object>;
                foreach(Dictionary<string,object> copy in copies)if((string)copy["id"]==(string)c["id"]&&car!=null&&(string)copy["carPin"]==(string)car["pin"])saved=copy;
                int i=grid.Rows.Add(c["name"],(Convert.ToDouble(c["bytes"])/1048576).ToString("F1"),(bool)c["active"]?(online?"Recording now":"Last seen recording • unconfirmed"):saved!=null?"PC backup ✓":"On car USB");
                if(saved!=null)grid.Rows[i].Tag=saved["file"];
                else if(online&&!(bool)c["active"])grid.Rows[i].Cells[0].Tag=c["id"];
            }
            foreach(Dictionary<string,object> copy in copies) {
                bool found=false;foreach(DataGridViewRow row in grid.Rows)if(row.Tag as string==(string)copy["file"])found=true;
                if(!found){int i=grid.Rows.Add(copy["name"],(Convert.ToDouble(copy["bytes"])/1048576).ToString("F1"),"PC backup • available offline");grid.Rows[i].Tag=copy["file"];}
            }
        } catch(Exception e){if(!IsDisposed){state.Text=(e is WebException?"PC SERVER UNAVAILABLE":"DASHBOARD REFRESH FAILED")+"\n"+e.Message;state.ForeColor=Color.Orange;location.Text="Location unavailable — refresh failed";}}
        finally {refreshing=false;}
    }
    void StartLive() {
        if(live!=null&&!live.HasExited)return;
        autoLiveAttempted=true;
        string ffplay=Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"ffplay.exe");
        if(!File.Exists(ffplay))throw new Exception("ffplay.exe missing from installation");
        live=new Process {StartInfo=new ProcessStartInfo(ffplay,"-loglevel error -fflags nobuffer -flags low_delay -framedrop -noinfbuf -sync ext -probesize 32768 -analyzeduration 0 -f mpegts -i pipe:0 -window_title \"RoadGuard Live\"") {
            UseShellExecute=false,RedirectStandardInput=true,CreateNoWindow=true}};
        live.Start();var process=live;
        liveStatus="Connecting live video…";
        Task.Run(async()=>{
            int failures=0;
            while(!closing&&!process.HasExited) {
              int retryDelay=0;
              try {
                var request=Request("/admin/live.avc");liveRequest=request;
                request.ReadWriteTimeout=15000;
                using(var response=await request.GetResponseAsync())using(var reader=new BinaryReader(response.GetResponseStream())) {
                    var transport=new LiveTransport(process.StandardInput.BaseStream);
                    while(!process.HasExited) {
                        int type=ReadInt(reader),size=ReadInt(reader);if(size<0||size>8*1024*1024)throw new InvalidDataException("Invalid live packet");
                        byte[] packet=reader.ReadBytes(size);if(packet.Length!=size)throw new EndOfStreamException();
                        using(var r=new BinaryReader(new MemoryStream(packet))) {
                            if(type==1) {ReadInt(r);ReadInt(r);int n=ReadInt(r);if(n<=0||n>65536||n>size-16)throw new InvalidDataException("Invalid SPS");byte[] sps=r.ReadBytes(n);int p=ReadInt(r);if(p<0||p>65536||p!=size-n-16)throw new InvalidDataException("Invalid PPS");byte[] pps=r.ReadBytes(p);transport.Configure(sps,pps);}
                            else if(type==2&&size>=13){long pts=ReadLong(r);int key=ReadInt(r);if(key!=0&&key!=1)throw new InvalidDataException("Invalid keyframe flag");transport.Frame(packet,12,size-12,pts,key==1);failures=0;System.Threading.Interlocked.Exchange(ref livePacketAt,Stopwatch.GetTimestamp());liveStatus="Receiving timestamped live video";}
                            else if(type!=3||size!=0)throw new InvalidDataException("Invalid live packet type");
                            process.StandardInput.BaseStream.Flush();
                        }
                    }
                }
              } catch(Exception ex) {
                if(closing||process.HasExited)break;
                var web=ex as WebException;var response=web==null?null:web.Response as HttpWebResponse;
                int code=response==null?0:(int)response.StatusCode;
                bool retry=!(ex is InvalidDataException)&&(ex is IOException||web!=null)&&
                    (code==0||code==429||code==502||code==503||code==504)&&
                    (web==null||(web.Status!=WebExceptionStatus.TrustFailure&&web.Status!=WebExceptionStatus.SecureChannelFailure));
                if(response!=null)response.Close();
                if(!retry){if(!IsDisposed&&!closing)BeginInvoke(new Action(()=>MessageBox.Show("Live stopped: "+ex.Message,"RoadGuard")));break;}
                failures=Math.Min(failures+1,6);
                retryDelay=Math.Min(30000,1000<<(failures-1));
                liveStatus="LIVE INTERRUPTED — last image may be stale; reconnecting automatically";
              } finally {liveRequest=null;}
              for(int waited=0;waited<retryDelay&&!closing&&!process.HasExited;waited+=250)await Task.Delay(250);
            }
            try{if(!process.HasExited)process.Kill();}catch{}
            liveStatus="Live window closed. Click Live video to reopen.";
        });
    }
    static int ReadInt(BinaryReader r) {var b=r.ReadBytes(4);if(b.Length!=4)throw new EndOfStreamException();return b[0]<<24|b[1]<<16|b[2]<<8|b[3];}
    static long ReadLong(BinaryReader r) {return ((long)(uint)ReadInt(r)<<32)|(uint)ReadInt(r);}
    [STAThread] static void Main(string[] args) {
        Application.EnableVisualStyles();
        try {Application.Run(new Dashboard(args.Length>0?args[0]:Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),"RoadGuard")));}
        catch(Exception e){MessageBox.Show("RoadGuard setup required: "+e.Message);}
    }
}
