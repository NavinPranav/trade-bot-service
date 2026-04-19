package com.sensex.optiontrader.service;
import com.sensex.optiontrader.exception.ResourceNotFoundException;
import com.sensex.optiontrader.model.dto.request.PositionRequest;
import com.sensex.optiontrader.model.entity.*;
import com.sensex.optiontrader.repository.PositionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
public class PortfolioService {
    private final PositionRepository repo;
    public List<Position> getPositions(Long uid) { return repo.findByUserIdAndIsOpenTrue(uid); }
    @Transactional public Position addPosition(Long uid, PositionRequest r) {
        return repo.save(Position.builder().user(User.builder().id(uid).build()).strategyType(r.getStrategyType()).optionType(r.getOptionType())
            .strikePrice(r.getStrikePrice()).expiry(r.getExpiry()).lots(r.getLots()).entryPrice(r.getEntryPrice()).isOpen(true).openedAt(LocalDateTime.now()).build());
    }
    @Transactional public Position closePosition(Long uid, Long pid, BigDecimal exit) {
        var p=repo.findById(pid).orElseThrow(()->new ResourceNotFoundException("Position","id",pid));
        if(!p.getUser().getId().equals(uid)) throw new ResourceNotFoundException("Position","id",pid);
        p.setExitPrice(exit); p.setIsOpen(false); p.setClosedAt(LocalDateTime.now()); return repo.save(p);
    }
    public Map<String,Object> getAggregateGreeks(Long uid) {
        var ps=repo.findByUserIdAndIsOpenTrue(uid); BigDecimal d=BigDecimal.ZERO,g=BigDecimal.ZERO,t=BigDecimal.ZERO,v=BigDecimal.ZERO;
        for(var p:ps){if(p.getDelta()!=null)d=d.add(p.getDelta());if(p.getGamma()!=null)g=g.add(p.getGamma());if(p.getTheta()!=null)t=t.add(p.getTheta());if(p.getVega()!=null)v=v.add(p.getVega());}
        return Map.of("delta",d.setScale(4,RoundingMode.HALF_UP),"gamma",g.setScale(4,RoundingMode.HALF_UP),"theta",t.setScale(4,RoundingMode.HALF_UP),"vega",v.setScale(4,RoundingMode.HALF_UP),"positionCount",ps.size());
    }
    public Map<String,Object> getPnl(Long uid) {
        var ps=repo.findByUserIdAndIsOpenTrue(uid); BigDecimal pnl=BigDecimal.ZERO;
        for(var p:ps) if(p.getCurrentPrice()!=null) pnl=pnl.add(p.getCurrentPrice().subtract(p.getEntryPrice()).multiply(BigDecimal.valueOf(p.getLots())));
        return Map.of("unrealizedPnl",pnl.setScale(2,RoundingMode.HALF_UP),"openPositions",ps.size());
    }
}