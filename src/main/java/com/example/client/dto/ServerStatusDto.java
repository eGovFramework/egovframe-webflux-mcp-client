package com.example.client.dto;

import java.util.List;

/**
 * 사이드바·적재 화면에 내보내는 서버 상태.
 *
 * `authFailed` 는 연결 실패 사유가 '인증' 인지 아닌지를 구분하는 값. 화면 안내가 달라져야 하기 때문
 * 인증 문제면 환경변수를 고쳐야 하고, 그 밖이면 서버를 띄우고 [연결] 을 눌러야 한다. 
 */
public record ServerStatusDto(
        String name,
        String type,
        String status,
        String lastError,
        boolean authFailed,
        List<String> tools,
        boolean restrictedAllowed
) {}
