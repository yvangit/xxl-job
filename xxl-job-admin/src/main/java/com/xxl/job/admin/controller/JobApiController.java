package com.xxl.job.admin.controller;

import com.xxl.job.admin.controller.annotation.PermessionLimit;
import com.xxl.job.admin.core.model.XxlJobInfo;
import com.xxl.job.admin.core.model.XxlJobLog;
import com.xxl.job.admin.core.schedule.XxlJobDynamicScheduler;
import com.xxl.job.admin.dao.IXxlJobGroupDao;
import com.xxl.job.admin.dao.IXxlJobInfoDao;
import com.xxl.job.admin.dao.IXxlJobLogDao;
import com.xxl.job.admin.dao.IXxlJobRegistryDao;
import com.xxl.job.admin.service.IXxlJobService;
import com.xxl.job.core.biz.model.HandleCallbackParam;
import com.xxl.job.core.biz.model.RegistryParam;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.util.AdminApiUtil;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.annotation.Resource;
import java.text.MessageFormat;
import java.util.Date;

/**
 * Created by xuxueli on 17/5/10.
 */
@Controller
public class JobApiController {
    private static Logger logger = LoggerFactory.getLogger(JobApiController.class);

    @Resource
    public IXxlJobLogDao xxlJobLogDao;
    @Resource
    private IXxlJobInfoDao xxlJobInfoDao;
    @Resource
    private IXxlJobRegistryDao xxlJobRegistryDao;
	@Resource
	private IXxlJobGroupDao xxlJobGroupDao;
	@Resource
	private IXxlJobService xxlJobService;
	
	

    @RequestMapping(value= AdminApiUtil.CALLBACK, method = RequestMethod.POST, consumes = "application/json")
    @ResponseBody
    @PermessionLimit(limit=false)
    public ReturnT<String> callback(@RequestBody HandleCallbackParam handleCallbackParam){


        // valid log item
        XxlJobLog log = xxlJobLogDao.load(handleCallbackParam.getLogId());
        if (log == null) {
            return new ReturnT<String>(ReturnT.FAIL_CODE, "log item not found.");
        }

        // trigger success, to trigger child job, and avoid repeat trigger child job
        String childTriggerMsg = null;
        if (ReturnT.SUCCESS_CODE==handleCallbackParam.getExecuteResult().getCode() && ReturnT.SUCCESS_CODE!=log.getHandleCode()) {
            XxlJobInfo xxlJobInfo = xxlJobInfoDao.loadById(log.getJobId());
            if (xxlJobInfo!=null && StringUtils.isNotBlank(xxlJobInfo.getChildJobKey())) {
                childTriggerMsg = "<hr>";
                String[] childJobKeys = xxlJobInfo.getChildJobKey().split(",");
                for (int i = 0; i < childJobKeys.length; i++) {
                    String[] jobKeyArr = childJobKeys[i].split("_");
                    if (jobKeyArr!=null && jobKeyArr.length==2) {
                        XxlJobInfo childJobInfo = xxlJobInfoDao.loadById(Integer.valueOf(jobKeyArr[1]));
                        if (childJobInfo!=null) {
                            try {
                                boolean ret = XxlJobDynamicScheduler.triggerJob(String.valueOf(childJobInfo.getId()), String.valueOf(childJobInfo.getJobGroup()));

                                // add msg
                                childTriggerMsg += MessageFormat.format("<br> {0}/{1} 触发子任务成功, 子任务Key: {2}, status: {3}, 子任务描述: {4}",
                                        (i+1), childJobKeys.length, childJobKeys[i], ret, childJobInfo.getJobDesc());
                            } catch (SchedulerException e) {
                                logger.error("", e);
                            }
                        } else {
                            childTriggerMsg += MessageFormat.format("<br> {0}/{1} 触发子任务失败, 子任务xxlJobInfo不存在, 子任务Key: {2}",
                                    (i+1), childJobKeys.length, childJobKeys[i]);
                        }
                    } else {
                        childTriggerMsg += MessageFormat.format("<br> {0}/{1} 触发子任务失败, 子任务Key格式错误, 子任务Key: {2}",
                                (i+1), childJobKeys.length, childJobKeys[i]);
                    }
                }

            }
        }

        // handle msg
        StringBuffer handleMsg = new StringBuffer();
        if (log.getHandleMsg()!=null) {
            handleMsg.append(log.getHandleMsg()).append("<br>");
        }
        if (handleCallbackParam.getExecuteResult().getMsg() != null) {
            handleMsg.append(handleCallbackParam.getExecuteResult().getMsg());
        }
        if (childTriggerMsg !=null) {
            handleMsg.append("<br>子任务触发备注：").append(childTriggerMsg);
        }

        // success, save log
        log.setHandleTime(new Date());
        log.setHandleCode(handleCallbackParam.getExecuteResult().getCode());
        log.setHandleMsg(handleMsg.toString());
        xxlJobLogDao.updateHandleInfo(log);

        return ReturnT.SUCCESS;
    }


    @RequestMapping(value=AdminApiUtil.REGISTRY, method = RequestMethod.POST, consumes = "application/json")
    @ResponseBody
    @PermessionLimit(limit=false)
    public ReturnT<String> registry(@RequestBody RegistryParam registryParam){
        int ret = xxlJobRegistryDao.registryUpdate(registryParam.getRegistGroup(), registryParam.getRegistryKey(), registryParam.getRegistryValue());
        if (ret < 1) {
            xxlJobRegistryDao.registrySave(registryParam.getRegistGroup(), registryParam.getRegistryKey(), registryParam.getRegistryValue());
        }
        return ReturnT.SUCCESS;
    }
    
    
    @RequestMapping(value=AdminApiUtil.JOB_UPDATE, method = RequestMethod.POST, consumes = "application/json")
    @ResponseBody
    @PermessionLimit(limit=false)
    public ReturnT<String> updatejob(@RequestBody XxlJobInfo jobInfo){
    	XxlJobInfo oldjobInfo = this.xxlJobService.loadByParameter(jobInfo);
        if (oldjobInfo == null) {
        	return this.xxlJobService.add(jobInfo);
        }
        if ((oldjobInfo.getJobCron().equals(jobInfo.getJobCron())) && (oldjobInfo.getJobDesc().equals(jobInfo.getJobDesc()))) {
        	return ReturnT.SUCCESS;
        }
        oldjobInfo.setJobCron(jobInfo.getJobCron());
        oldjobInfo.setJobDesc(jobInfo.getJobDesc());
        return this.xxlJobService.reschedule(oldjobInfo);
    }
    
    @RequestMapping(value=AdminApiUtil.JOB_DELETE, method = RequestMethod.POST, consumes = "application/json")
    @ResponseBody
    @PermessionLimit(limit=false)
    public ReturnT<String> deletejob(@RequestBody XxlJobInfo jobInfo){
    	XxlJobInfo oldjobInfo=xxlJobService.loadByParameter(jobInfo);
    	if(oldjobInfo==null){
    		return ReturnT.SUCCESS;
    	}else{
    		return xxlJobService.remove(oldjobInfo.getId());
    	}
    }
    
    
    @RequestMapping(value=AdminApiUtil.JOB_INFO, method = RequestMethod.POST, consumes = "application/json")
    @ResponseBody
    @PermessionLimit(limit=false)
    public JSONObject jobinfo(@RequestBody XxlJobInfo jobInfo){
    	jobInfo=xxlJobService.loadByParameter(jobInfo);
    	JSONObject json=new JSONObject();
    	if(jobInfo==null){
    		json.put("msg","没有获取到Job信息");
    		json.put("success",false);
    	}else{
    		json.put("success",true);
    		json.put("jobinfo", jobInfo);
    		json.put("msg","获取成功");
    	}
        return json;
    }
    @RequestMapping(value=AdminApiUtil.JOB_START, method = RequestMethod.POST, consumes = "application/json")
    @ResponseBody
    @PermessionLimit(limit=false)
    public ReturnT<String> sartjob(@RequestBody XxlJobInfo jobInfo){
    	XxlJobInfo oldjobInfo=xxlJobService.loadByParameter(jobInfo);
    	if(oldjobInfo==null){
    		return ReturnT.SUCCESS;
    	}else{
    		return xxlJobService.resume(oldjobInfo.getId());
    	}
    }
    @RequestMapping(value=AdminApiUtil.JOB_STOP, method = RequestMethod.POST, consumes = "application/json")
    @ResponseBody
    @PermessionLimit(limit=false)
    public ReturnT<String> stopjob(@RequestBody XxlJobInfo jobInfo){
    	XxlJobInfo oldjobInfo=xxlJobService.loadByParameter(jobInfo);
    	if(oldjobInfo==null){
    		return ReturnT.SUCCESS;
    	}else{
    		return xxlJobService.pause(oldjobInfo.getId());
    	}
    }
}
