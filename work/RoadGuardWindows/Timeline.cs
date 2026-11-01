using System;
using System.IO;
using System.Text;
using System.Linq;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

// Works from completed PC copies, even when the car is offline.
public sealed class Timeline:Form {
    readonly DateTimePicker day=new DateTimePicker(),start=new DateTimePicker(),end=new DateTimePicker();
    readonly CheckBox whole=new CheckBox();readonly ComboBox speed=new ComboBox();
    readonly Label status=new Label();readonly Button watch=new Button(),save=new Button();
    readonly List<KeyValuePair<DateTime,string>> files=new List<KeyValuePair<DateTime,string>>();
    readonly CancellationTokenSource cancel=new CancellationTokenSource();
    static string Q(string path){return "\""+path+"\"";}
    static string Seconds(double v){return v.ToString("0.000",CultureInfo.InvariantCulture);}
    static string Tool(string name){return Path.Combine(AppDomain.CurrentDomain.BaseDirectory,name+".exe");}
    public Timeline(string root,System.Collections.IEnumerable copies,string pin){
        Text="RoadGuard • Recording timeline";Size=new Size(555,340);BackColor=Color.FromArgb(17,28,41);ForeColor=Color.White;Font=new Font("Segoe UI",11);
        foreach(Dictionary<string,object> copy in copies){
            if(!String.Equals(copy["carPin"] as string,pin,StringComparison.Ordinal))continue;
            string p=Path.GetFullPath(Path.Combine(root,(string)copy["file"]));DateTime t;
            string n=Path.GetFileNameWithoutExtension((string)copy["name"]);
            if(p.StartsWith(Path.GetFullPath(Path.Combine(root,"Recordings"))+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)&&
               DateTime.TryParseExact(n,"yyyy-MM-dd_dddd_HH-mm-ss-fff",CultureInfo.InvariantCulture,DateTimeStyles.None,out t)&&File.Exists(p))
                files.Add(new KeyValuePair<DateTime,string>(t,p));
        }
        files.Sort((a,b)=>a.Key.CompareTo(b.Key));if(files.Count>0)day.Value=files[files.Count-1].Key.Date;
        var layout=new FlowLayoutPanel{Dock=DockStyle.Fill,Padding=new Padding(16),AutoScroll=true};
        layout.Controls.Add(new Label{Text="Saved footage • car clock",AutoSize=true});day.Format=DateTimePickerFormat.Short;layout.Controls.Add(day);layout.SetFlowBreak(day,true);
        whole.Text="Full day";whole.AutoSize=true;whole.Checked=true;layout.Controls.Add(whole);
        foreach(var picker in new[]{start,end}){picker.Format=DateTimePickerFormat.Custom;picker.CustomFormat="HH:mm";picker.ShowUpDown=true;picker.Width=90;picker.Enabled=false;layout.Controls.Add(picker);}
        start.Value=DateTime.Today.AddHours(9);end.Value=DateTime.Today.AddHours(10);layout.SetFlowBreak(end,true);
        whole.CheckedChanged+=(s,e)=>{start.Enabled=end.Enabled=!whole.Checked;};
        speed.DropDownStyle=ComboBoxStyle.DropDownList;speed.Items.AddRange(new[]{"1×","2×","3×"});speed.SelectedIndex=0;speed.Width=62;layout.Controls.Add(speed);
        watch.Text="Play selection";save.Text="Save MP4…";
        foreach(var b in new[]{watch,save}){b.AutoSize=true;b.BackColor=Color.FromArgb(26,85,112);layout.Controls.Add(b);}
        layout.SetFlowBreak(save,true);status.Size=new Size(480,110);status.Text=files.Count+" completed PC copies. Use Back up now in the dashboard for clips still on the car.";layout.Controls.Add(status);
        watch.Click+=async(s,e)=>await Run(true);save.Click+=async(s,e)=>await Run(false);Controls.Add(layout);
        FormClosing+=(s,e)=>cancel.Cancel();
    }
    static string Execute(string tool,string args,CancellationToken stop) {
        stop.ThrowIfCancellationRequested();
        using(var p=new Process{StartInfo=new ProcessStartInfo(Tool(tool),args){UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true,RedirectStandardOutput=true}}){
            p.Start();var err=p.StandardError.ReadToEndAsync();var output=p.StandardOutput.ReadToEndAsync();
            using(stop.Register(()=>{try {if(!p.HasExited)p.Kill();}catch{}})){p.WaitForExit();}
            stop.ThrowIfCancellationRequested();if(p.ExitCode!=0)throw new IOException(tool+" failed: "+err.Result);
            return output.Result;
        }
    }
    async Task Run(bool preview){
        DateTime from=day.Value.Date.Add(whole.Checked?TimeSpan.Zero:new TimeSpan(start.Value.Hour,start.Value.Minute,0));
        DateTime until=whole.Checked?day.Value.Date.AddDays(1):day.Value.Date.Add(new TimeSpan(end.Value.Hour,end.Value.Minute,0));
        if(until<=from){status.Text="End time must be later than start.";return;}
        string destination=null;int rate=speed.SelectedIndex+1;
        if(!preview)using(var dlg=new SaveFileDialog{Filter="MP4 video|*.mp4",FileName=from.ToString("yyyy-MM-dd_HH-mm")+"_to_"+until.ToString("HH-mm")+".mp4"}){
            if(dlg.ShowDialog()!=DialogResult.OK)return;destination=dlg.FileName;
            if(File.Exists(destination)){status.Text="Please choose a new file name. Existing files are not overwritten.";return;}
        }
        watch.Enabled=save.Enabled=false;status.Text="Preparing selected footage on this PC. Missing time is skipped. Close this window to cancel.";
        try {await Task.Run(()=>ProcessSelection(files,from,until,preview,destination,rate,cancel.Token));if(!IsDisposed)status.Text=preview?"Playback finished.":"Saved: "+destination;}
        catch(Exception e){if(!IsDisposed)status.Text=e.Message;}
        finally{if(!IsDisposed)watch.Enabled=save.Enabled=true;}
    }
    internal static void ProcessSelection(List<KeyValuePair<DateTime,string>> files,DateTime from,DateTime until,bool preview,string destination,int rate,CancellationToken token){
            var tmp=Directory.CreateDirectory(Path.Combine(Path.GetTempPath(),"RoadGuard-clip-"+Guid.NewGuid().ToString("N"))).FullName;
            var made=new List<string>();string partial=null;
            try {
                var candidates=files.Where(f=>f.Key<from).Reverse().Take(1).Concat(files.Where(f=>f.Key>=from&&f.Key<until));
                var names=new List<string>();int width=0,height=0;
                foreach(var f in candidates){
                    string[] fields=Execute("ffprobe","-v error -select_streams v:0 -show_entries stream=width,height,duration -of csv=p=0 "+Q(f.Value),token).Trim().Split(',');
                    double duration;if(fields.Length!=3||!Double.TryParse(fields[2],NumberStyles.Float,CultureInfo.InvariantCulture,out duration)||duration<=0)throw new IOException("Cannot read duration of "+Path.GetFileName(f.Value));
                    double a=Math.Max(0,(from-f.Key).TotalSeconds),b=Math.Min(duration,(until-f.Key).TotalSeconds);if(a>=b)continue;
                    if(width==0){width=Int32.Parse(fields[0]);height=Int32.Parse(fields[1]);}
                    string name=names.Count.ToString("D6")+".mp4",cut=Path.Combine(tmp,name);made.Add(cut);
                    Execute("ffmpeg","-nostdin -v error -ss "+Seconds(a)+" -i "+Q(f.Value)+" -t "+Seconds(b-a)+" -an -vf scale="+width+":"+height+":force_original_aspect_ratio=decrease,pad="+width+":"+height+":(ow-iw)/2:(oh-ih)/2,setsar=1 -c:v libx264 -threads 2 -preset veryfast -crf 18 -pix_fmt yuv420p -video_track_timescale 90000 "+Q(cut),token);
                    names.Add("file '"+name+"'");
                }
                if(names.Count==0)throw new IOException("No backed-up footage in that time range.");
                string manifest=Path.Combine(tmp,"parts.txt");made.Add(manifest);File.WriteAllLines(manifest,names,new UTF8Encoding(false));
                string movie=Path.Combine(tmp,"selection.mp4");made.Add(movie);
                Execute("ffmpeg","-nostdin -v error -f concat -safe 1 -i "+Q(manifest)+" -c copy -movflags +faststart "+Q(movie),token);
                if(preview)Execute("ffplay","-loglevel error -autoexit -an -vf setpts=PTS/"+rate+" -window_title \"RoadGuard timeline\" "+Q(movie),token);
                else {partial=destination+"."+Guid.NewGuid().ToString("N")+".partial";File.Copy(movie,partial);token.ThrowIfCancellationRequested();File.Move(partial,destination);partial=null;}
            }finally{if(partial!=null&&File.Exists(partial))File.Delete(partial);foreach(var item in made)if(File.Exists(item))File.Delete(item);Directory.Delete(tmp,false);}
    }
}
