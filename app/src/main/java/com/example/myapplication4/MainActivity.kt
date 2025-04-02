package com.example.myapplication4

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import com.permissionx.guolindev.PermissionX


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btncamera.setOnClickListener { btn->
            cameraProviderResult.launch(android.Manifest.permission.CAMERA)
        }
    }


    private val cameraProviderResult = registerForActivityResult(ActivityResultContracts.RequestPermission()){ permissao->
        if(permissao){
            iniciarCamera()
        }else{
            Snackbar.make(binding.root, "Permissão Negada", Snackbar.LENGTH_INDEFINITE).show()
        }
    }


    private fun iniciarCamera(){
        var intentCamera = Intent(this, CameraPreviewActivity::class.java)
        startActivity(intentCamera)
    }
}