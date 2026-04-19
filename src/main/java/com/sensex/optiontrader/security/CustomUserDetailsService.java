package com.sensex.optiontrader.security;

import com.sensex.optiontrader.exception.ResourceNotFoundException;
import com.sensex.optiontrader.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {
    private final UserRepository userRepo;
    @Override public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        return UserPrincipal.from(userRepo.findByEmail(email).orElseThrow(() -> new UsernameNotFoundException("Not found: " + email)));
    }
    public UserDetails loadUserById(Long id) {
        return UserPrincipal.from(userRepo.findById(id).orElseThrow(() -> new ResourceNotFoundException("User", "id", id)));
    }
}