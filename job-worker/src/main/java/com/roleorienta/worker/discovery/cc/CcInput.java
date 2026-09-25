package com.roleorienta.worker.discovery.cc;

import com.roleorienta.worker.adapters.personio.PersonioAdapter;
import com.roleorienta.worker.adapters.personio.PersonioBoard;
import com.roleorienta.worker.adapters.workday.WorkdayAdapter;
import com.roleorienta.worker.adapters.workday.WorkdayBoard;
import java.util.Optional;

/**
 * Вход обнаружения по индексу Common Crawl (§55, §91): какой шаблон адресов читать, какой системе
 * найма принадлежат доски и как адрес страницы превращается в доску. У каждого входа свой курсор
 * обхода ({@code harvest_cursor.input_code}). Включённые входы — {@code app.discovery.cc.inputs}.
 */
public enum CcInput {

    /** Workday: {@code <tenant>.wdN.myworkdayjobs.com/<locale>/<site>/…} (§54). */
    WORKDAY("cc-workday", "*.myworkdayjobs.com", WorkdayAdapter.PROVIDER_CODE),

    /** Personio, немецкий домен: {@code <аккаунт>.jobs.personio.de} (§91). */
    PERSONIO_DE("cc-personio-de", "*.jobs.personio.de", PersonioAdapter.PROVIDER_CODE),

    /** Personio, международный домен: {@code <аккаунт>.jobs.personio.com} (§91). */
    PERSONIO_COM("cc-personio-com", "*.jobs.personio.com", PersonioAdapter.PROVIDER_CODE);

    private final String code;
    private final String urlPattern;
    private final String providerCode;

    CcInput(String code, String urlPattern, String providerCode) {
        this.code = code;
        this.urlPattern = urlPattern;
        this.providerCode = providerCode;
    }

    /** @return код входа — ключ курсора и накопителя досок */
    public String code() {
        return code;
    }

    /** @return шаблон URL для CDX-запроса индекса */
    public String urlPattern() {
        return urlPattern;
    }

    /** @return код системы найма досок входа */
    public String providerCode() {
        return providerCode;
    }

    /**
     * Доска по адресу страницы из индекса.
     *
     * @param url адрес страницы
     * @return доска; пусто — адрес не с доски этой системы найма
     */
    public Optional<HarvestedBoard> board(String url) {
        return switch (this) {
            case WORKDAY -> WorkdayBoard.fromCareerUrl(url)
                    .map(board -> new HarvestedBoard(board.slug(), board.dedupKey(), board.baseUrl()));
            case PERSONIO_DE, PERSONIO_COM -> PersonioBoard.fromCareerUrl(url)
                    .map(board -> new HarvestedBoard(board.slug(), board.dedupKey(), board.baseUrl()));
        };
    }
}
