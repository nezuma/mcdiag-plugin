package net.mcdiag.agent.economy;

import org.bukkit.OfflinePlayer;

/** Плагин валюты, найденный на сервере. Вызывается только из главного потока. */
interface EconomySource {

    /** Короткий код для панели: {@code vault}, {@code playerpoints}. */
    String id();

    /** Имя плагина, который на самом деле хранит деньги: «EssentialsX Economy», «CMIEconomy». */
    String name();

    /** Название валюты во множественном числе или null. */
    String unit();

    /** @return баланс или null, если узнать не вышло */
    Double balance(OfflinePlayer player);
}
