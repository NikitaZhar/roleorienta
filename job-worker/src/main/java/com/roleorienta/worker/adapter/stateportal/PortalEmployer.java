package com.roleorienta.worker.adapter.stateportal;

/**
 * Работодатель из списка государственного портала.
 *
 * @param registrationNumber IČO (восемь цифр)
 * @param name               название, как его показывает портал
 */
public record PortalEmployer(String registrationNumber, String name) {
}
