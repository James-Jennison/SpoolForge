package net.jamesjennison.filamajignfc
import android.app.Application
import net.jamesjennison.filamajignfc.data.DataStore
class FilamajigApplication:Application(){ val data by lazy { DataStore(this) } }
