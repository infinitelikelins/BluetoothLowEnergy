package com.bearya.robot.bluetoothlowenergy

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.icu.text.SimpleDateFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ParcelUuid
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.os.postDelayed
import androidx.core.widget.NestedScrollView
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        // 服务启动UUID
        private val UUID_SERVICE: UUID = UUID.fromString("7e87d2af-4781-4a48-a827-abfa6b322e47")

        // 服务可写+通知 BluetoothGattCharacteristic UUID
        private val UUID_CHAR_WRITE_NOTIFY = UUID.fromString("a0de9f4b-4f42-4dd9-a655-7140b15159fe")

        // 服务可读+通知 BluetoothGattCharacteristic UUID
//        private val UUID_CHAR_READ_NOTIFY = UUID.fromString("a16156d4-9b8c-4a1e-a3d8-a699240d97fe")
    }

    private var startBluetoothGattFlag = false
    private val bluetoothDevices: MutableList<BluetoothDevice> by lazy { mutableListOf() }
    private val mBluetoothLeAdvertiser: BluetoothLeAdvertiser? by lazy { BluetoothAdapter.getDefaultAdapter().bluetoothLeAdvertiser } // BLE广播
    private var mBluetoothGattServer: BluetoothGattServer? = null // BLE服务端
    private lateinit var textView: AppCompatTextView
    private lateinit var scrollView: NestedScrollView
    private val handler by lazy { Handler() }
    private val runnable: Runnable by lazy {
        Runnable { scrollView.fullScroll(NestedScrollView.FOCUS_DOWN) }
    }
    private var responseString: StringBuffer = StringBuffer()

    // BLE广播Callback
    private val mAdvertiseCallback by lazy {
        object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) = logger("广播 : 添加启动成功")
            override fun onStartFailure(errorCode: Int) = logger("广播 : 添加启动失败 , 错误码 = $errorCode")
        }
    }
    private val sdf by lazy { SimpleDateFormat("HH : mm : ss", Locale.CHINA) }
    private val mBluetoothGattServerCallback by lazy {
        object : BluetoothGattServerCallback() {
            override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                logger(when (status) {
                    0 -> when (newState) {
                        BluetoothGatt.STATE_CONNECTED -> device?.takeUnless { bluetoothDevices.contains(it) }?.also { bluetoothDevices.add(it) }?.let { "onConnectionStateChange :  与 [ ${it.name} ] 连接成功 , 等待接收解析数据。" }
                        BluetoothGatt.STATE_DISCONNECTED -> device?.takeIf { bluetoothDevices.contains(it) }?.also { bluetoothDevices.remove(it) }?.let { "onConnectionStateChange :  与 [ ${it.name} ] 连接断开" }
                        else -> "onConnectionStateChange :  与 [ ${device?.name} ] 正在发生连接断开变化中"
                    }
                    else -> device?.takeIf { bluetoothDevices.contains(it) }?.also { bluetoothDevices.remove(it) }?.let { "onConnectionStateChange :  与 [ ${it.name} ] 连接出错,错误码:$status" }
                })
            }

            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                logger("onServiceAdded : 添加服务 [ ${service?.uuid} ] ${if (status == 0) "成功" else "失败 , 错误码 : $status"} 。")
            }

            override fun onCharacteristicReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic?) {
                val response = "CharacteristicReadResponse_${(Math.random() * 100).toInt()}" //模拟数据
                mBluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response.toByteArray()) // 响应客户端
                logger("onCharacteristicReadRequest : 客户端 [ ${device?.name} ] 读取Characteristic : $response")
            }

            override fun onCharacteristicWriteRequest(device: BluetoothDevice?, requestId: Int, characteristic: BluetoothGattCharacteristic?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {

                mBluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) // 响应客户端

                if (value != null && value.isNotEmpty()) {
                    if ((value[0].toInt().and(0xff) == 0xf8) && (value[value.size - 1].toInt().and(0xff) == 0xf9)) {
                        // 首Byte == 0xf8 && 尾Byte == 0xf9 , 结束
                        val characteristicByte = value.copyOfRange(3, value.size - 2).toString(Charsets.UTF_8)
                        logger("onCharacteristicWriteRequest : 客户端 [ ${device?.name} ] 写入Characteristic  : $characteristicByte")

                        mBluetoothGattServer?.notifyCharacteristicChanged(device, characteristic?.apply { setValue("SUCCESS") }, true)
                                ?.takeIf { it }?.also { logger("onDescriptorWriteRequest: 通知客户端 [ ${device?.name} ] 改变Characteristic : SUCCESS") }

                    } else if ((value[0].toInt().and(0xff) == 0xf8) && (value[value.size - 1].toInt().and(0xff) != 0xf9)) {
                        // 首Byte == 0xf8 && 尾Byte != 0xf9 , 继续
                        responseString.append(value.copyOfRange(3, value.size).toString(Charsets.UTF_8))
                    } else if ((value[0].toInt().and(0xff) != 0xf8) && (value[value.size - 1].toInt().and(0xff) == 0xf9)) {
                        // 首Byte != 0xf8 && 尾Byte == 0xf9 , 结束
                        if (value.size > 2) {
                            responseString.append(value.copyOfRange(0, value.size - 2).toString(Charsets.UTF_8))
                        } else if (value.size == 1) {
                            responseString.deleteCharAt(responseString.length - 1)
                        }
                        logger("onCharacteristicWriteRequest : 客户端 [ ${device?.name} ] 写入Characteristic  : $responseString")

                        mBluetoothGattServer?.notifyCharacteristicChanged(device, characteristic?.apply { setValue("SUCCESS") }, true)
                                ?.takeIf { it }?.also { logger("onDescriptorWriteRequest: 通知客户端 [ ${device?.name} ] 改变Characteristic : SUCCESS") }

                    } else {
                        // 首Byte != 0xf8 && 尾Byte != 0xf9 , 继续
                        responseString.append(value.toString(Charsets.UTF_8))
                    }
                }

            }

            override fun onDescriptorReadRequest(device: BluetoothDevice?, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor?) {
                val response = "DescriptorReadResponse_${(Math.random() * 100).toInt()}" //模拟数据
                mBluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response.toByteArray()) // 响应客户端
                logger("onDescriptorReadRequest : 客户端 [ ${device?.name} ] 读取Descriptor : $response")
            }

            override fun onDescriptorWriteRequest(device: BluetoothDevice?, requestId: Int, descriptor: BluetoothGattDescriptor?, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?) {
                // 获取客户端发过来的数据
                val valueStr = value?.toString(Charsets.UTF_8)
                mBluetoothGattServer?.getService(UUID_SERVICE)?.getCharacteristic(UUID_CHAR_WRITE_NOTIFY)
                mBluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) // 响应客户端
                logger("onDescriptorWriteRequest: 客户端 [ ${device?.name} ] 写入Descriptor : $valueStr")
            }

            override fun onExecuteWrite(device: BluetoothDevice?, requestId: Int, execute: Boolean) = logger("onExecuteWrite : name =  ${device?.name} , address = ${device?.address} , requestId = $requestId , exe = $execute")

            override fun onNotificationSent(device: BluetoothDevice?, status: Int) = logger("onNotificationSent : name =  ${device?.name} , address = ${device?.address} , status = $status")

            override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) = logger("onMtuChanged : name =  ${device?.name} , address = ${device?.address} , mtu = $mtu")

        }

    }

    @SuppressLint("HardwareIds")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textView = findViewById(R.id.message)
        scrollView = findViewById(R.id.nested)

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        when (BluetoothAdapter.getDefaultAdapter().state) {
                            BluetoothAdapter.STATE_ON -> logger("蓝牙已经打开, 点击上方 '启动蓝牙连接服务' ")
                            BluetoothAdapter.STATE_OFF -> startBluetoothGattFlag = false.also { logger("蓝牙已经关闭，点击打开蓝牙试试") }
                            BluetoothAdapter.STATE_TURNING_OFF -> logger("蓝牙正在关闭")
                            BluetoothAdapter.STATE_TURNING_ON -> logger("蓝牙正在打开")
                        }
                    }
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        if (packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            BluetoothAdapter.getDefaultAdapter().apply {
                name = "BeiYa-${Build.SERIAL}".also { logger("本机支持BluetoothLowEnergy , 可搜索本机蓝牙设备名称 : $it") }
            }
            openBluetooth()
        } else {
            logger("本机不支持BluetoothLowEnergy")
        }
    }

    private fun initBluetoothLowEnergy() {
        startBluetoothGattFlag = true
        logger("启动BluetoothLowEnergyService")
        // 广播设置
        val advertiseSettingsBuilder = AdvertiseSettings.Builder().apply {
            setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY) // 广播模式: 低功耗,平衡,低延迟
            setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH) // 发射功率级别: 极低,低,中,高
            setConnectable(true)  // 能否连接,广播分为可连接广播和不可连接广播,必须要开启可连接的BLE广播,其它设备才能发现并连接BLE服务端
        }.build()
        // 广播数据
        val advertiseDataBuild = AdvertiseData.Builder().apply {
            setIncludeDeviceName(true) // 包含蓝牙名称
            setIncludeTxPowerLevel(true) // 包含发射功率级别
        }.build()
        // 扫描响应数据
        val advertiseResponseDataBuild = AdvertiseData.Builder().apply {
            addServiceUuid(ParcelUuid(UUID_SERVICE)) // 服务UUID
        }.build()

        mBluetoothLeAdvertiser?.startAdvertising(advertiseSettingsBuilder, advertiseDataBuild, advertiseResponseDataBuild, mAdvertiseCallback)

        val bluetoothGattService = BluetoothGattService(UUID_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            // 添加指定UUID的可写characteristic
            addCharacteristic(BluetoothGattCharacteristic(UUID_CHAR_WRITE_NOTIFY, BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_WRITE))
        }
        // 添加 可读+通知 characteristic
        mBluetoothGattServer = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager?)?.openGattServer(this, mBluetoothGattServerCallback)
        mBluetoothGattServer?.addService(bluetoothGattService)
    }

    private fun logger(message: String? = "\n******************************************\n") {
        runOnUiThread {
            handler.removeCallbacks(runnable)
            textView.append("\n${sdf.format(Date())}\n【 $message 】\n")
            textView.append("\n=======================================================================\n")
            handler.postDelayed(runnable, 30)
            responseString.takeIf { it.isNotEmpty() }?.apply { delete(0, responseString.length) }
        }
    }

    private fun openBluetooth() {
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            if (!BluetoothAdapter.getDefaultAdapter().isEnabled) {
                logger("正在启动蓝牙,等候回应")
                BluetoothAdapter.getDefaultAdapter().enable()
            } else {
                logger("蓝牙已经打开, 点击上方 '启动蓝牙连接服务' ")
            }
        } else {
            logger("本机不支持BluetoothLowEnergy")
        }
    }

    private fun stopBluetooth() {
        logger("BluetoothLowEnergyService 正在关闭Advertising,清除Services,释放Server")

        mBluetoothLeAdvertiser?.stopAdvertising(mAdvertiseCallback)
        bluetoothDevices.takeIf { it.size > 0 }?.forEachIndexed { _, bluetoothDevice ->
            mBluetoothGattServer?.cancelConnection(bluetoothDevice)
        }
        mBluetoothGattServer?.clearServices()
        mBluetoothGattServer?.close()
        mBluetoothGattServer = null

        logger("BluetoothLowEnergyService 结束关闭Advertising,清除Services,释放Server")

        startBluetoothGattFlag = false
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.bluetooth_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> finish()
            R.id.start_bluetooth_service -> {
                if (startBluetoothGattFlag) {
                    logger("BluetoothLowEnergyService已经启动")
                } else {
                    logger("5秒后启动BluetoothLowEnergyService")
                    handler.postDelayed(5000) {
                        initBluetoothLowEnergy()
                    }
                }
            }
            R.id.stop_bluetooth_service -> {
                if (startBluetoothGattFlag) stopBluetooth()
                else logger("BluetoothLowEnergyService 已经关闭")
            }
            R.id.open_bluetooth -> openBluetooth()
        }
        return super.onOptionsItemSelected(item)
    }

}