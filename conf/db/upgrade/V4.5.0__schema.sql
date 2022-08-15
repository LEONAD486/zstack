CREATE TABLE IF NOT EXISTS `zstack`.`AiSiNoSecretResourcePoolVO` (
    `uuid` varchar(32) NOT NULL UNIQUE,
    `managementIp` varchar(32) NOT NULL,
    `port` int unsigned NOT NULL,
    `route` varchar(32) NOT NULL,
    `clientID` varchar(32) NOT NULL,
    `clientSecrete` varchar(32) NOT NULL,
    `appId` varchar(8) NOT NULL,
    `keyNumSM2` varchar(8) NOT NULL,
    `keyNumSM4` varchar(8) NOT NULL,
    PRIMARY KEY  (`uuid`),
    CONSTRAINT fkAiSiNoSecretResourcePoolVOSecretResourcePoolVO FOREIGN KEY (uuid) REFERENCES SecretResourcePoolVO (uuid) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;
ALTER TABLE SecretResourcePoolVO ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'Connected';
ALTER TABLE `zstack`.`TicketStatusHistoryVO` ADD COLUMN `flowName` VARCHAR(255) DEFAULT NULL;
ALTER TABLE `zstack`.`ArchiveTicketStatusHistoryVO` ADD COLUMN `flowName` VARCHAR(255) DEFAULT NULL;

CREATE TABLE IF NOT EXISTS `zstack`.`CephOsdGroupVO` (
    `uuid` varchar(32) NOT NULL,
    `primaryStorageUuid` varchar(32) NOT NULL,
    `osds` varchar(1024) NOT NULL,
    `availableCapacity` bigint(20) DEFAULT NULL,
    `availablePhysicalCapacity` bigint(20) unsigned NOT NULL DEFAULT 0,
    `totalPhysicalCapacity` bigint(20) unsigned NOT NULL DEFAULT 0,
    `lastOpDate` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00' ON UPDATE CURRENT_TIMESTAMP,
    `createDate` timestamp NOT NULL DEFAULT '0000-00-00 00:00:00',
    PRIMARY KEY (`uuid`),
    KEY `fkPrimaryStorageUuid` (`primaryStorageUuid`),
    CONSTRAINT `fkPrimaryStorageUuid` FOREIGN KEY (`primaryStorageUuid`) REFERENCES `PrimaryStorageEO` (`uuid`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

ALTER TABLE `zstack`.`CephPrimaryStoragePoolVO` ADD COLUMN `osdGroupUuid` VARCHAR(32) DEFAULT NULL;
ALTER TABLE `zstack`.`CephPrimaryStoragePoolVO` ADD CONSTRAINT fkCephPrimaryStoragePoolVOOsdGroupVO FOREIGN KEY (osdGroupUuid) REFERENCES CephOsdGroupVO (uuid) ON DELETE SET NULL;

CREATE TABLE IF NOT EXISTS `zstack`.`VxlanHostMappingVO` (
    `id` bigint unsigned NOT NULL UNIQUE AUTO_INCREMENT,
    `vxlanUuid` varchar(32) NOT NULL,
    `hostUuid` varchar(32) NOT NULL,
    `vlanId` int,
    `physicalInterface` varchar(32),
    `createDate`   timestamp    NOT NULL DEFAULT '0000-00-00 00:00:00',
    `lastOpDate`   timestamp    NOT NULL DEFAULT '0000-00-00 00:00:00' ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY  (`id`),
    CONSTRAINT `fkVxlanHostMappingVOVxlanNetworkVO` FOREIGN KEY (`vxlanUuid`) REFERENCES `VxlanNetworkVO` (`uuid`) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fkVxlanHostMappingVOHostEO` FOREIGN KEY (`hostUuid`) REFERENCES `HostEO` (`uuid`) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE IF NOT EXISTS `zstack`.`VxlanClusterMappingVO` (
    `id` bigint unsigned NOT NULL UNIQUE AUTO_INCREMENT,
    `vxlanUuid` varchar(32) NOT NULL,
    `clusterUuid` varchar(32) NOT NULL,
    `vlanId` int,
    `physicalInterface` varchar(32),
    `createDate`   timestamp    NOT NULL DEFAULT '0000-00-00 00:00:00',
    `lastOpDate`   timestamp    NOT NULL DEFAULT '0000-00-00 00:00:00' ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY  (`id`),
    CONSTRAINT `fkVxlanClusterMappingVOVxlanNetworkVO` FOREIGN KEY (`vxlanUuid`) REFERENCES `VxlanNetworkVO` (`uuid`) ON UPDATE RESTRICT ON DELETE CASCADE,
    CONSTRAINT `fkVxlanClusterMappingVOClusterEO` FOREIGN KEY (`clusterUuid`) REFERENCES `ClusterEO` (`uuid`) ON UPDATE RESTRICT ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8;