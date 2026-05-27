package com.plum.reader.auth

class EmailAlreadyTakenException(val email: String) :
    RuntimeException("email already taken: $email")

class InvalidCredentialsException :
    RuntimeException("invalid credentials")
