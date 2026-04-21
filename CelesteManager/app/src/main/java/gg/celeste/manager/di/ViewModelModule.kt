package gg.celeste.manager.di

import gg.celeste.manager.ui.viewmodel.home.HomeViewModel
import gg.celeste.manager.ui.viewmodel.installer.InstallerViewModel
import gg.celeste.manager.ui.viewmodel.installer.LogViewerViewModel
import gg.celeste.manager.ui.viewmodel.libraries.LibrariesViewModel
import gg.celeste.manager.ui.viewmodel.settings.AdvancedSettingsViewModel
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.module

val viewModelModule = module {
    factoryOf(::InstallerViewModel)
    factoryOf(::AdvancedSettingsViewModel)
    factoryOf(::HomeViewModel)
    factoryOf(::LogViewerViewModel)
    factoryOf(::LibrariesViewModel)
}