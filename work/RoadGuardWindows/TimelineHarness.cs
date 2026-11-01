using System;
using System.Collections.Generic;
using System.Threading;
class TimelineHarness {
    static void Main(string[] args){
        var day=new DateTime(2026,11,1);var files=new List<KeyValuePair<DateTime,string>>();
        files.Add(new KeyValuePair<DateTime,string>(day.AddSeconds(-2),args[0]));
        files.Add(new KeyValuePair<DateTime,string>(day.AddSeconds(5),args[0]));
        Timeline.ProcessSelection(files,day,day.AddDays(1),false,args[1],1,CancellationToken.None);
        Timeline.ProcessSelection(files,day.AddSeconds(1),day.AddSeconds(7),false,args[2],3,CancellationToken.None);
    }
}
