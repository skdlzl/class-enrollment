import { test, expect } from '@playwright/test';
import assert from 'node:assert/strict';
test('request lifecycle and student isolation', async ({ page }) => {
 await page.setViewportSize({width:1440,height:1000});
 let state='QUEUED',posts=0;
 const ticket={requestId:'11111111-1111-1111-1111-111111111111',studentId:1,courseId:1,courseName:'분산시스템',status:'QUEUED',message:'신청이 접수되었습니다. 확정 결과를 기다려 주세요.',createdAt:'2026-10-16T07:30:00Z'};
 await page.route('**/api/enrollment-requests**',async route=>{
  const url=new URL(route.request().url());
  let data;
  if(url.pathname.endsWith('/options'))data={students:[{id:1,name:'김지윤',maxCredits:18},{id:2,name:'이서준',maxCredits:18}],courses:[{id:1,code:'CSE301',name:'분산시스템',professor:'김교수',department:'컴퓨터공학과',credits:3,capacity:30,enrolled:0},{id:2,code:'CSE302',name:'데이터베이스',professor:'이교수',department:'컴퓨터공학과',credits:3,capacity:40,enrolled:0}],schedules:[{courseId:1,day:'MONDAY',start:'09:00',end:'10:30'}]};
  else if(route.request().method()==='POST'){posts++;data=ticket;}
  else data={tickets:posts && url.searchParams.get('studentId')==='1' ? [{...ticket,status:state,message:state==='CONFIRMED'?'수강신청이 확정되었습니다.':ticket.message}]:[],enrollments:state==='CONFIRMED'&&url.searchParams.get('studentId')==='1'?[{id:1,name:'분산시스템',credits:3}]:[]};
  await route.fulfill({status:route.request().method()==='POST'?202:200,contentType:'application/json',body:JSON.stringify(data)});
 });
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('http://127.0.0.1:5173');
 await page.getByRole('button',{name:'신청',exact:true}).first().click();
 await page.getByText('접수 완료 · 대기 중',{exact:true}).waitFor();
 assert.equal(posts,1);
 await page.screenshot({path:'test-results/queue-desktop.png',fullPage:true});
 await page.reload();await page.getByText('접수 완료 · 대기 중',{exact:true}).waitFor();
 state='CONFIRMED';await page.getByText('수강신청이 확정되었습니다.',{exact:true}).waitFor();
 await page.getByLabel('현재 학생 선택').selectOption('2');
 await page.getByText('접수한 신청이 없습니다.',{exact:true}).waitFor();
 await page.reload();await expect(page.getByLabel('현재 학생 선택')).toHaveValue('2');
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:'test-results/queue-mobile.png',fullPage:true});
 assert.deepEqual(errors,[]);
 console.log('PASS: submit, pending, reload recovery, confirmation, student isolation, selected-student persistence, mobile render');
});
