package com.sensex.optiontrader.service;
import com.sensex.optiontrader.exception.ResourceNotFoundException;
import com.sensex.optiontrader.model.dto.request.AlertRequest;
import com.sensex.optiontrader.model.entity.*;
import com.sensex.optiontrader.repository.AlertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service @RequiredArgsConstructor
public class AlertService {
    private final AlertRepository repo;
    public List<Alert> getActiveAlerts(Long uid) { return repo.findByUserIdAndActiveTrue(uid); }
    @Transactional public Alert createAlert(Long uid, AlertRequest r) {
        return repo.save(Alert.builder().user(User.builder().id(uid).build()).alertType(r.getAlertType()).threshold(r.getThreshold()).description(r.getDescription()).active(true).triggered(false).createdAt(LocalDateTime.now()).build());
    }
    @Transactional public void deleteAlert(Long uid, Long id) {
        var a=repo.findById(id).orElseThrow(()->new ResourceNotFoundException("Alert","id",id));
        if(!a.getUser().getId().equals(uid)) throw new ResourceNotFoundException("Alert","id",id);
        repo.delete(a);
    }
}