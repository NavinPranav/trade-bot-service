package com.sensex.optiontrader.service;
import com.sensex.optiontrader.exception.*;
import com.sensex.optiontrader.grpc.MlServiceClient;
import com.sensex.optiontrader.model.dto.request.BacktestRequest;
import com.sensex.optiontrader.model.entity.*;
import com.sensex.optiontrader.model.enums.BacktestStatus;
import com.sensex.optiontrader.repository.BacktestJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j @Service @RequiredArgsConstructor
public class BacktestService {
    private final BacktestJobRepository repo;
    private final MlServiceClient ml;

    @Transactional public Map<String,Object> submitBacktest(Long uid, BacktestRequest req) {
        long active = repo.countByUserIdAndStatus(uid,BacktestStatus.RUNNING)+repo.countByUserIdAndStatus(uid,BacktestStatus.PENDING);
        if(active>=3) throw new BacktestLimitExceededException("Max 3 concurrent backtests");
        var job = BacktestJob.builder().user(User.builder().id(uid).build()).strategyType(req.getStrategyType()).startDate(req.getStartDate()).endDate(req.getEndDate()).parameters(req.getParameters()).createdAt(LocalDateTime.now()).build();
        job = repo.save(job); executeAsync(job.getId());
        return Map.of("jobId",job.getId(),"status",job.getStatus(),"message","Submitted");
    }
    @Async public void executeAsync(Long id) {
        try {
            var j = repo.findById(id).orElseThrow();
            j.setStatus(BacktestStatus.RUNNING);
            repo.save(j);
            Map<String, Object> result = ml.runBacktest(j);
            j.setResultDetails(result);
            j.setStatus(BacktestStatus.COMPLETED);
            j.setProgressPercent(100);
            j.setCompletedAt(LocalDateTime.now());
            repo.save(j);
        } catch (Exception e) {
            log.error("Backtest {} failed", id, e);
            repo.findById(id).ifPresent(j -> {
                j.setStatus(BacktestStatus.FAILED);
                repo.save(j);
            });
        }
    }
    public Map<String,Object> getStatus(Long id) { var j=repo.findById(id).orElseThrow(()->new ResourceNotFoundException("Job","id",id)); return Map.of("jobId",j.getId(),"status",j.getStatus(),"progress",j.getProgressPercent()!=null?j.getProgressPercent():0); }
    public Map<String,Object> getResults(Long id) { var j=repo.findById(id).orElseThrow(()->new ResourceNotFoundException("Job","id",id)); return Map.of("jobId",j.getId(),"status",j.getStatus(),"details",j.getResultDetails()!=null?j.getResultDetails():Map.of()); }
}