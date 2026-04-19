package com.sensex.optiontrader.security;

import com.sensex.optiontrader.model.entity.User;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import java.util.*;

@Getter @AllArgsConstructor
public class UserPrincipal implements UserDetails {
    private Long id; private String email; private String password;
    private Collection<? extends GrantedAuthority> authorities;

    public static UserPrincipal from(User u) {
        return new UserPrincipal(u.getId(), u.getEmail(), u.getPassword(), List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name())));
    }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return true; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return true; }
}