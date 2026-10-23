import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.net.URI
import java.net.http.*
import java.time.Duration
import org.apache.jmeter.samplers.SampleResult as JMeterSampleResult

def client=props.computeIfAbsent('dbSplitHttpClient',{k->HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build()})
def json=new JsonSlurper()
def root=vars.get('base')
def run=props.getProperty('runId')
def kind=vars.get('kind')
def student=ctx.getThreadNum()+1
def requests=[]
def call={String path,String method,Object body,String label ->
    def sample=new JMeterSampleResult();sample.setSampleLabel(label);sample.setDataType(JMeterSampleResult.TEXT);sample.sampleStart()
    def result=[status:0,body:null]
    try {
        def builder=HttpRequest.newBuilder(URI.create(root+path)).timeout(Duration.ofSeconds(10))
        if(method=='POST')builder.header('Content-Type','application/json').POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
        else builder.GET()
        def response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString())
        result.status=response.statusCode()
        try{result.body=json.parseText(response.body())}catch(Exception ignored){}
        sample.setResponseCode(result.status.toString());sample.setSuccessful(result.status<400)
        sample.setResponseMessage(result.body instanceof Map?(result.body.code?:'OK'):'OK')
    }catch(Exception e){sample.setResponseCode('CLIENT_UNKNOWN');sample.setResponseMessage(e.class.simpleName);sample.setSuccessful(false)}
    finally{sample.sampleEnd()}
    sample.setResponseData(JsonOutput.toJson([status:result.status,code:result.body instanceof Map?result.body.code:null,requestId:body?.requestId]),'UTF-8')
    SampleResult.addSubResult(sample)
    requests.add([kind:label,status:result.status,code:result.body instanceof Map?result.body.code:null,elapsedMs:sample.time,startedAt:sample.startTime,requestId:body?.requestId])
    return result
}
if(kind!='journey'){
    int i=Integer.parseInt(vars.get('iteration')?:'0');vars.put('iteration',(i+1).toString())
    def path=kind=='academic'?"/api/subjects?runId=${run}&department=D${i%10}":"/api/offerings?runId=${run}&department=D${i%10}&grade=${(int)(i/10)%4+1}"
    def r=call(path,'GET',null,kind+'-http')
    SampleResult.setSuccessful(r.status==200);SampleResult.setResponseCode(r.status.toString())
    SampleResult.setResponseData(JsonOutput.toJson([kind:kind,requests:requests]),'UTF-8')
    return
}
// 500 distinct students, one journey each; alternative attempts stay sequential.
root='http://127.0.0.1:'+(student%2==0?'28080':'28081')
def tried=[];String outcome='ATTEMPT_LIMIT';long started=System.nanoTime()
for(int attempt=0;attempt<3;attempt++){
    if((System.nanoTime()-started)/1e6>60000){outcome='DEADLINE';break}
    def prefix="/api/offerings?runId=${run}&department=D0&grade=1"
    def first=call(prefix,'GET',null,'journeyRead');if(first.status!=200){outcome='READ_FAILED';break}
    long cursor=first.body.isEmpty()?0:(first.body.collect{it.id}.max() as long)
    def second=call(prefix+"&afterId=${cursor}",'GET',null,'journeyRead');if(second.status!=200){outcome='READ_FAILED';break}
    def search=call(prefix+'&keyword='+URLEncoder.encode('수강실습','UTF-8'),'GET',null,'journeyRead');if(search.status!=200){outcome='READ_FAILED';break}
    def available=search.body.findAll{it.id<=5&&!tried.contains(it.id)&&it.enrolledCount<it.capacity}.sort{it.id}
    if(available.isEmpty()){outcome='NO_AVAILABLE_COURSE';break}
    def course=attempt==0?available[0]:available[(student-1)%available.size()];tried.add(course.id)
    String rid=UUID.randomUUID().toString()
    def result=call('/api/enrollments','POST',[requestId:rid,runId:run,studentId:student,termId:202602,courseId:course.id],'submit')
    if(result.status==201 && result.body?.status=='CONFIRMED'){outcome='CONFIRMED';break}
    if(result.status==0 || result.body?.code=='DATABASE_RESULT_UNKNOWN'){outcome='SUBMIT_UNKNOWN';break}
    if(result.status!=409 || result.body?.code!='COURSE_FULL'){outcome='SUBMIT_FAILED';break}
}
SampleResult.setSuccessful(outcome=='CONFIRMED')
SampleResult.setResponseCode(outcome=='CONFIRMED'?'200':'409');SampleResult.setResponseMessage(outcome)
SampleResult.setResponseData(JsonOutput.toJson([studentId:student,outcome:outcome,requests:requests]),'UTF-8')
