package com.roleorienta.worker.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.roleorienta.worker.career.BoardDiscoveryHandler;
import com.roleorienta.worker.career.CareerScanHandler;
import com.roleorienta.worker.collect.ReadSourceHandler;
import com.roleorienta.worker.delivery.RunPassHandler;
import com.roleorienta.worker.geo.GeoImportHandler;
import com.roleorienta.worker.intake.CompanySizeHandler;
import com.roleorienta.worker.intake.RegistryIntakeHandler;
import com.roleorienta.worker.match.MatchVacanciesHandler;
import com.roleorienta.worker.site.SiteNameHandler;
import com.roleorienta.worker.site.SiteScanHandler;
import com.roleorienta.worker.site.WikidataSiteHandler;
import com.roleorienta.worker.stateportal.ProfesiaListHandler;
import com.roleorienta.worker.stateportal.ProfesiaSiteHandler;
import com.roleorienta.worker.stateportal.StatePortalHandler;
import org.junit.jupiter.api.Test;

/**
 * Выбор очереди по типу задания (стенограмма §46): типы поиска записаны в топологии строками — тест
 * сверяет их с константами обработчиков, чтобы переименование типа не увело задание в чужую очередь.
 */
class RabbitTopologyTests {

    /**
     * Поиск — реестр, сайты, кадровые страницы, обратный путь, портал, справочник — в очередь поиска.
     */
    @Test
    void discoveryTasksGoToDiscoveryQueue() {
        assertThat(RabbitTopology.DISCOVERY_TYPES).containsExactlyInAnyOrder(RegistryIntakeHandler.TYPE,
                SiteScanHandler.TYPE, WikidataSiteHandler.TYPE, CareerScanHandler.TYPE, BoardDiscoveryHandler.TYPE,
                StatePortalHandler.TYPE, GeoImportHandler.TYPE, CompanySizeHandler.TYPE, ProfesiaSiteHandler.TYPE,
                ProfesiaListHandler.TYPE);
        assertThat(RabbitTopology.routingKey(StatePortalHandler.TYPE)).isEqualTo(RabbitTopology.DISCOVERY_ROUTING_KEY);
    }

    /**
     * Адрес по названию — в свою очередь; тип в топологии совпадает с константой обработчика.
     */
    @Test
    void siteNameTasksGoToTheirQueue() {
        assertThat(RabbitTopology.SITE_NAME_TYPE).isEqualTo(SiteNameHandler.TYPE);
        assertThat(RabbitTopology.routingKey(SiteNameHandler.TYPE)).isEqualTo(RabbitTopology.SITE_NAME_ROUTING_KEY);
    }

    /**
     * Чтение источников — в очередь сбора.
     */
    @Test
    void collectionTasksGoToWorkQueue() {
        assertThat(RabbitTopology.routingKey(ReadSourceHandler.TYPE)).isEqualTo(RabbitTopology.ROUTING_KEY);
    }

    /**
     * Проходы выдачи и соответствия — во внутреннюю очередь, не за чтениями (стенограмма §95); типы в топологии
     * совпадают с константами обработчиков.
     */
    @Test
    void internalTasksGoToInternalQueue() {
        assertThat(RabbitTopology.INTERNAL_TYPES).containsExactlyInAnyOrder(RunPassHandler.TYPE,
                MatchVacanciesHandler.TYPE);
        for (String type : RabbitTopology.INTERNAL_TYPES) {
            assertThat(RabbitTopology.routingKey(type)).isEqualTo(RabbitTopology.INTERNAL_ROUTING_KEY);
        }
    }
}
