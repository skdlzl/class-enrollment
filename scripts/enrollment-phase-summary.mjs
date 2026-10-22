// Cumulative counter deltas. Never subtract lifetime maxima or label means as P95.
export function summarizeEnrollmentPhases(diagnostics) {
  return (diagnostics?.after??[]).flatMap(after=>{
    const before=diagnostics.before?.find(x=>x.base===after.base);
    const a=after.body?.enrollmentTimings,b=before?.body?.enrollmentTimings;
    if(after.status!==200||before?.status!==200||!a?.enabled||!b?.enabled||a.version!==b.version)
      return [{base:after.base,error:'Phase diagnostics missing or incompatible'}];
    return Object.entries(a.phases).map(([phase,end])=>{
      const start=b.phases[phase]??{count:0,failures:0,totalMs:0};
      const count=end.count-start.count, failures=end.failures-start.failures,totalMs=end.totalMs-start.totalMs;
      if(count<0||failures<0||totalMs<0)return {base:after.base,phase,error:'Counters reset during measurement'};
      return {base:after.base,phase,count,failures,meanMs:count?Number((totalMs/count).toFixed(3)):null,
        totalMs:Number(totalMs.toFixed(3)),lifetimeMaxMs:end.maxMs};
    });
  });
}
