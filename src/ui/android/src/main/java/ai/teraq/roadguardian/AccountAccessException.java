package ai.teraq.roadguardian;

import java.io.IOException;

/**
 * 사용자에게 보여 주는 오류에는 서버 응답 원문이나 인증 정보를 넣지 않는다
 */
public final class AccountAccessException extends IOException {
    public enum Reason {NOT_CONFIGURED, NOT_FOUND, SIGN_IN, EXPIRED, CHANGED, UNAVAILABLE, RESPONSE, CONFLICT, FORBIDDEN, INCOMPATIBLE, NOT_SUPPORTED, INTEGRITY}

    public final Reason reason;

    public AccountAccessException(Reason reason) {
        super(message(reason));
        this.reason = reason;
    }

    public boolean canRetry() {
        return reason == Reason.UNAVAILABLE || reason == Reason.RESPONSE || reason == Reason.CHANGED;
    }

    private static String message(Reason reason) {
        switch (reason) {
            case NOT_CONFIGURED:
                return "차량 계정 서비스가 아직 준비되지 않았습니다. 주차 사진은 저장한 휴대전화에서 확인해 주세요.";
            case NOT_FOUND:
                return "이 사건을 조회할 수 없습니다. 목록에서 다시 확인해 주세요.";
            case SIGN_IN:
                return "차량 기록을 보려면 계정을 연결해 주세요.";
            case EXPIRED:
                return "로그인 시간이 만료됐습니다. 계정을 다시 연결해 주세요.";
            case CHANGED:
                return "계정 정보가 변경됐습니다. 기록을 다시 확인해 주세요.";
            case CONFLICT:
                return "다른 곳에서 메모가 수정됐습니다. 서버 메모를 다시 확인해 주세요.";
            case FORBIDDEN:
                return "이 계정에는 메모 저장 권한이 없습니다. 계정 연결을 확인해 주세요.";
            case INCOMPATIBLE:
                return "앱과 서비스의 연결 방식이 다릅니다. 앱 업데이트 안내를 확인해 주세요.";
            case NOT_SUPPORTED:
                return "이 서비스는 개인 확인 메모를 제공하지 않습니다. 사건 기록은 계속 확인할 수 있습니다.";
            case INTEGRITY:
                return "자료 묶음의 원본과 파일 목록이 일치하는지 확인하지 못해 저장을 중단했습니다. 사건을 다시 조회해 주세요.";
            case UNAVAILABLE:
                return "지금은 차량 기록을 받을 수 없습니다. 잠시 후 다시 확인해 주세요.";
            default:
                return "받은 기록을 확인하지 못했습니다. 잠시 후 다시 확인해 주세요.";
        }
    }
}
