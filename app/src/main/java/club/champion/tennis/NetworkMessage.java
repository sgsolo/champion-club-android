package club.champion.tennis;

/** Connection failures must not expose integration configuration in the consumer UI. */
final class NetworkMessage {
    static final String ERROR="Не удалось подключиться. Проверьте интернет и повторите попытку.";
    static String safe(String message){
        if(message==null||message.isEmpty())return ERROR;
        String lower=message.toLowerCase(java.util.Locale.ROOT);
        for(String term:new String[]{"token","токен","api-ключ","api ключ","partner","authorization","bearer"})if(lower.contains(term))return ERROR;
        return message;
    }
}
