package ru.mephi.api;

import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(100)
public class CommonApiExceptionHandler extends AbstractCommonApiExceptionHandler {}
