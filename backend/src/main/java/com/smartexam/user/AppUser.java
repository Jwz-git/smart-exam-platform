package com.smartexam.user;

public record AppUser(long id, String username, String passwordHash, String displayName, Role role, Status status) {
    public enum Role { ADMIN, TEACHER, STUDENT }
    public enum Status { ACTIVE, DISABLED }
}
