import json,pathlib,collections,math,re,argparse
parser=argparse.ArgumentParser(description="Recompute pooled latency and metric deltas from three extracted CI artifacts")
parser.add_argument('--root',default='/tmp/exchange-comparison')
parser.add_argument('--output',default='/tmp/exchange-comparison-analysis.json')
parser.add_argument('--expected-trials',type=int,default=18)
parser.add_argument('--commit',required=True)
parser.add_argument('--run-id',required=True,type=int)
args=parser.parse_args()
root=pathlib.Path(args.root)
trials=[]
def measure(snapshot,name,stat):
    return sum(next((m['value'] for m in node.get(name,{}).get('measurements',[]) if m['statistic']==stat),0) for node in snapshot.values())
for path in sorted(root.glob('*/**/summary.json')):
    obj=json.loads(path.read_text())
    if 'strategy' not in obj:continue
    folder=path.parent
    responses=json.loads((folder/'responses.json').read_text());terminal=json.loads((folder/'terminal.json').read_text());metrics=json.loads((folder/'metrics.json').read_text())
    assert len(responses)==len(terminal)==12000
    assert collections.Counter(str(r['http']) for r in responses)==obj['initial_http']
    assert collections.Counter(r['phase'] for r in terminal)==obj['terminal_states']
    starts={r['index']+1:r['started_epoch'] for r in responses}
    latency=[max(0,(float(r['terminal_epoch'])-starts[r['student_id']])*1000) for r in terminal if r['phase']=='COMPLETED']
    delta=lambda name,stat:measure(metrics['after'],name,stat)-measure(metrics['before'],name,stat)
    causes={}
    for node in metrics['after'].values():
        for name in node:
            if name.startswith('exchange.retries:'):causes[name.split(':',1)[1]]=delta(name,'COUNT')
    obj['metrics']={'queue_deferrals':delta('exchange.queued','COUNT'),'partner_calls':delta('exchange.partner','COUNT'),'partner_seconds':delta('exchange.partner','TOTAL_TIME'),'partner_actions':{action:delta('exchange.partner:'+action,'COUNT') for action in ['status','reserve','confirm','cancel']},'db_usage_seconds':delta('hikaricp.connections.usage','TOTAL_TIME'),'db_acquire_seconds':delta('hikaricp.connections.acquire','TOTAL_TIME'),'db_pool_timeouts':delta('hikaricp.connections.timeout','COUNT'),'retry_causes':causes}
    obj['_latencies']=latency;trials.append(obj)
assert len(trials)==args.expected_trials,len(trials)
aggregates=[]
for workload in ['distributed','hot']:
    for strategy in ['PESSIMISTIC','OPTIMISTIC','REDIS']:
        rows=[r for r in trials if r['strategy']==strategy and r['workload']==workload]
        if not rows:continue
        assert len(rows)==3
        lats=sorted(v for r in rows for v in r['_latencies']);counts=collections.Counter();causes=collections.Counter();actions=collections.Counter()
        for r in rows:counts.update(r['initial_http']);causes.update(r['metrics']['retry_causes']);actions.update(r['metrics']['partner_actions'])
        result={'strategy':strategy,'workload':workload,'requests':sum(r['unique_requests'] for r in rows),'created':sum(r['terminal_states']['COMPLETED'] for r in rows),'initial_http':dict(counts),'mean_settlement_seconds':sum(r['settlement_seconds'] for r in rows)/3,'mean_settled_rps':sum(r['settled_rps'] for r in rows)/3,'pooled_settled_rps':sum(r['unique_requests'] for r in rows)/sum(r['settlement_seconds'] for r in rows),'completed_p95_ms':lats[math.ceil(len(lats)*.95)-1],'completed_p99_ms':lats[math.ceil(len(lats)*.99)-1],'client_retries':sum(r['client_retries'] for r in rows),'attempts':sum(r['attempts'] for r in rows),'partner_actions':dict(actions),'retry_causes':dict(causes)}
        for field in ['queue_deferrals','partner_calls','partner_seconds','db_usage_seconds','db_acquire_seconds','db_pool_timeouts']:result[field]=sum(r['metrics'][field] for r in rows)
        aggregates.append(result)
for r in trials:del r['_latencies']
out={'tested_commit':args.commit,'run_id':args.run_id,'trials':trials,'aggregates':aggregates}
pathlib.Path(args.output).write_text(json.dumps(out,indent=2));print(json.dumps(aggregates,indent=2))
