package com.example.newta

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.AsyncTask
import android.os.Bundle
import android.provider.ContactsContract.Data
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.newta.model.Distance
import com.example.newta.model.LatLngModel
import com.example.newta.model.MapDataModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.libraries.places.api.Places
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ktx.database
import com.google.firebase.database.ktx.getValue
import com.google.firebase.ktx.Firebase
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.roundToInt


class MainActivity2 : AppCompatActivity(), OnMapReadyCallback {
    private lateinit var mMap: GoogleMap
    private lateinit var btnNearestNeighbor: Button
    private lateinit var textJarakContent: TextView
    private lateinit var locationList: List<Location>
    private var distance: Double = 0.0;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main2)
        val ai: ApplicationInfo = applicationContext.packageManager
            .getApplicationInfo(applicationContext.packageName, PackageManager.GET_META_DATA)
        val value = ai.metaData["com.google.android.geo.API_KEY"]
        val apiKey = value.toString()

        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, apiKey)
        }
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        val database = Firebase.database.reference
        btnNearestNeighbor = findViewById(R.id.btn_nearest_neighbor)
        textJarakContent = findViewById(R.id.text_jarak_content)
        locationList = ArrayList()

        database.child("db").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(dataSnapshot: DataSnapshot) {
                val dataList = mutableListOf<LatLngModel>()
//              Mengubah data dari Firebase menjadi list
                for (snapshot in dataSnapshot.children) {
                    val data = snapshot.getValue<LatLngModel>()
                    dataList.add(data!!)
                }

                //Menggunakan algoritma nearest neighbor
                val nearest = nearestNeighbor(dataList)

                //Menampilkan data terdekat pada Google Maps
                for (nearestData in nearest) {
                    val data = LatLng(nearestData.latitude, nearestData.longitude)
                    val title = nearestData.id
                    val markerOptions = MarkerOptions().position(data).title(title)
                    mMap.addMarker(markerOptions)
                }

                val originLocation = LatLng(nearest[0].latitude, nearest[0].longitude)
                val destinationLocation =
                    LatLng(nearest[nearest.size - 1].latitude, nearest[nearest.size - 1].longitude)
                nearest.removeLast();
                nearest.removeFirst();
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(originLocation, 13F))


                btnNearestNeighbor.setOnClickListener {
                    val urll = getDirectionURL(originLocation, nearest, destinationLocation, apiKey)
                    GetDirection(urll).execute()
                    mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(originLocation, 14F))

                }
            }

            override fun onCancelled(databaseError: DatabaseError) {
                //Menangani error saat mengambil data dari Firebase
            }
        })
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
    }

    private fun nearestNeighbor(locations: List<LatLngModel>): MutableList<LatLngModel> {
        val result = mutableListOf<LatLngModel>()
        for (i in locations.indices) {
            var closestLocation = locations[0]
            var closestDistance = distance(locations[i], closestLocation)
            for (j in locations.indices) {
                if (j == i) continue
                val distance = distance(locations[i], locations[j])
                if (distance < closestDistance) {
                    closestLocation = locations[j]
                    closestDistance = distance
                }
            }
            result.add(locations[i])
        }
        return result
    }

    private fun distance(locationA: LatLngModel, locationB: LatLngModel): Double {
        val earthRadius = 6371.0 // km
        val latDistance = Math.toRadians(locationB.latitude - locationA.latitude)
        val lngDistance = Math.toRadians(locationB.longitude - locationA.longitude)
        val a = (Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + (Math.cos(Math.toRadians(locationA.latitude)) * Math.cos(Math.toRadians(locationB.latitude))
                * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2)))
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }

    private fun getDirectionURL(
        origin: LatLng,
        nearest: List<LatLngModel>,
        dest: LatLng,
        secret: String
    ): String {
        val waypoints = nearest.map { "${it.latitude},${it.longitude}" }.joinToString("|")
        return "https://maps.googleapis.com/maps/api/directions/json?origin=${origin.latitude},${origin.longitude}" +
                "&destination=${dest.latitude},${dest.longitude}" +
                "&waypoints=${waypoints}" +
                "&sensor=false" +
                "&mode=driving" +
                "&key=$secret"
    }

    @SuppressLint("StaticFieldLeak")
    private inner class GetDirection(val url: String) :
        AsyncTask<Void, Void, List<List<LatLng>>>() {
        override fun doInBackground(vararg params: Void?): List<List<LatLng>> {
            val client = OkHttpClient()
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val data = response.body!!.string()

            val result = ArrayList<List<LatLng>>()
            try {
                val respObj = Gson().fromJson(data, MapDataModel::class.java)
                val json = JSONObject(data)
                val routes = json.getJSONArray("routes")
                val path = ArrayList<LatLng>()
                for (i in 0 until routes.length()) {
                    val route = routes.getJSONObject(i)
                    val overviewPolyline = route.getJSONObject("overview_polyline")
                    val points = overviewPolyline.getString("points")

                    val legs = route.getJSONArray("legs");

                    for (j in 0 until legs.length()) {
                        distance += route.getJSONArray("legs")
                            .getJSONObject(j)
                            .getJSONObject("distance")
                            .getInt("value")
                    }



                    val decodedPoints = decodePolyline(points)
                    path.addAll(decodedPoints);
                }
                result.add(path)

                textJarakContent.setText(
                    (((distance / 1000) * 100.0).roundToInt()/100.0).toString() + " km")
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return result
        }

        override fun onPostExecute(result: List<List<LatLng>>) {
            for (route in result) {
                val lineoption = PolylineOptions()
                    .color(Color.BLUE)
                    .width(10f)
                    .addAll(route)
                mMap.addPolyline(lineoption)
            }
        }
    }

    fun decodePolyline(encoded: String): List<LatLng> {
        val poly = ArrayList<LatLng>()
        var index = 0
        val len = encoded.length
        var lat = 0
        var lng = 0
        while (index < len) {
            var b: Int
            var shift = 0
            var result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lat += dlat
            shift = 0
            result = 0
            do {
                b = encoded[index++].code - 63
                result = result or (b and 0x1f shl shift)
                shift += 5
            } while (b >= 0x20)
            val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
            lng += dlng
            val latLng = LatLng((lat.toDouble() / 1E5), (lng.toDouble() / 1E5))
            poly.add(latLng)
        }
        return poly
    }
}

